//! Starting operations on the runtime and reporting their results to Java.
//!
//! Each native method receives a `NativeCall` object and passes it to [`start`]. The
//! result, success or failure, is reported by calling one `on*` method on that object.
//! Java waits on the `NativeCall`, so it can be interrupted, and builds any exception on
//! the waiting thread.

use std::collections::HashMap;
use std::future::Future;
use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, PoisonError};

use bytes::Bytes;
use futures::FutureExt as _;
use futures::future::{AbortHandle, Abortable};
use jni::objects::{Global, JClass, JObject, JValue};
use jni::sys::jlong;
use jni::{Env, EnvUnowned, JavaVM, jni_sig, jni_str};

use crate::error::{ErrorKind, NativeError, NativeResult};
use crate::runtime::{self, Runtime};

/// A successful result, tagged with the `NativeCall` method that receives it.
#[derive(Debug)]
pub enum Reply {
    Void,
    Bool(bool),
    Long(i64),
    /// `None` means the key does not exist.
    Bytes(Option<Bytes>),
    Text(Option<String>),
    Texts(Vec<String>),
    BytesList(Vec<Option<Bytes>>),
}

static NEXT_TASK: AtomicI64 = AtomicI64::new(1);
static TASKS: LazyLock<Mutex<HashMap<i64, AbortHandle>>> =
    LazyLock::new(Default::default);

/// Start an operation and return its task id, which Java may pass to `cancel`.
///
/// `prepare` runs on the calling Java thread. It reads the Java arguments and returns
/// the future to run; any error it returns is reported through the callback before
/// `start` returns. A task id of 0 means the result has already been reported.
pub fn start<P, F>(
    env: &mut Env<'_>,
    anchor: &JClass<'_>,
    callback: &JObject<'_>,
    prepare: P,
) -> NativeResult<jlong>
where
    P: FnOnce(&mut Env<'_>, &Arc<Runtime>) -> NativeResult<F>,
    F: Future<Output = NativeResult<Reply>> + Send + 'static,
{
    if runtime::on_runtime_thread() {
        report(
            env,
            callback,
            Err(NativeError::new(
                ErrorKind::RuntimeThread,
                "icechunk was called from one of its own runtime threads",
            )),
        )?;
        return Ok(0);
    }
    let prepared = runtime::shared(env, anchor)
        .and_then(|runtime| prepare(env, &runtime).map(|future| (runtime, future)));
    let (runtime, future) = match prepared {
        Ok(prepared) => prepared,
        Err(err) => {
            report(env, callback, Err(err))?;
            return Ok(0);
        }
    };
    let callback = env.new_global_ref(callback)?;
    let id = NEXT_TASK.fetch_add(1, Ordering::Relaxed);
    let (abort, registration) = AbortHandle::new_pair();
    tasks().insert(id, abort);
    let owner = Arc::clone(&runtime);
    runtime.handle()?.spawn(async move {
        let outcome =
            Abortable::new(AssertUnwindSafe(future).catch_unwind(), registration).await;
        tasks().remove(&id);
        // An aborted task reports nothing: Java cancelled it and stopped waiting.
        if let Ok(result) = outcome {
            let result = result.unwrap_or_else(|panic| Err(panic_error(&*panic)));
            report_from_worker(&callback, result);
        }
        drop(owner);
    });
    Ok(id)
}

/// Abort a running task. Unknown or finished ids are ignored.
pub(crate) fn cancel(id: jlong) {
    if let Some(abort) = tasks().remove(&id) {
        abort.abort();
    }
}

fn tasks() -> std::sync::MutexGuard<'static, HashMap<i64, AbortHandle>> {
    TASKS.lock().unwrap_or_else(PoisonError::into_inner)
}

fn panic_error(payload: &(dyn std::any::Any + Send)) -> NativeError {
    let message = payload
        .downcast_ref::<&str>()
        .map(|s| (*s).to_owned())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "non-string panic payload".to_owned());
    NativeError::new(ErrorKind::Panic, message)
}

/// Report a result from a runtime worker, which is already attached to the JVM.
fn report_from_worker(callback: &Global<JObject<'static>>, result: NativeResult<Reply>) {
    let Ok(vm) = JavaVM::singleton() else { return };
    let _ = vm.attach_current_thread(|env| -> NativeResult<()> {
        if let Err(err) = report(env, callback.as_obj(), result) {
            // Building the reply failed, typically an OutOfMemoryError allocating the
            // array. Clear it and report the failure so the waiting thread wakes up.
            env.exception_clear();
            let _ = report(env, callback.as_obj(), Err(err));
        }
        // An exception thrown by the callback has nowhere to propagate to.
        env.exception_clear();
        Ok(())
    });
}

fn report(
    env: &mut Env<'_>,
    callback: &JObject<'_>,
    result: NativeResult<Reply>,
) -> NativeResult<()> {
    match result {
        Ok(Reply::Void) => {
            env.call_method(callback, jni_str!("onVoid"), jni_sig!("()V"), &[])?;
        }
        Ok(Reply::Bool(value)) => {
            env.call_method(
                callback,
                jni_str!("onBoolean"),
                jni_sig!("(Z)V"),
                &[JValue::Bool(value)],
            )?;
        }
        Ok(Reply::Long(value)) => {
            env.call_method(
                callback,
                jni_str!("onLong"),
                jni_sig!("(J)V"),
                &[JValue::Long(value)],
            )?;
        }
        Ok(Reply::Bytes(value)) => {
            let array = match value {
                Some(bytes) => JObject::from(env.byte_array_from_slice(&bytes)?),
                None => JObject::null(),
            };
            env.call_method(
                callback,
                jni_str!("onBytes"),
                jni_sig!("([B)V"),
                &[JValue::Object(&array)],
            )?;
        }
        Ok(Reply::Text(value)) => {
            let text = match value {
                Some(text) => JObject::from(env.new_string(text)?),
                None => JObject::null(),
            };
            env.call_method(
                callback,
                jni_str!("onString"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&text)],
            )?;
        }
        Ok(Reply::Texts(values)) => {
            let array = env.new_object_array(
                jsize(values.len())?,
                jni_str!("java/lang/String"),
                JObject::null(),
            )?;
            for (index, value) in values.iter().enumerate() {
                let value = env.new_string(value)?;
                array.set_element(env, index, &value)?;
            }
            env.call_method(
                callback,
                jni_str!("onStrings"),
                jni_sig!("([Ljava/lang/String;)V"),
                &[JValue::Object(&array)],
            )?;
        }
        Ok(Reply::BytesList(values)) => {
            let array = env.new_object_array(
                jsize(values.len())?,
                jni_str!("[B"),
                JObject::null(),
            )?;
            for (index, value) in values.iter().enumerate() {
                if let Some(bytes) = value {
                    let bytes = env.byte_array_from_slice(bytes)?;
                    array.set_element(env, index, &bytes)?;
                }
            }
            env.call_method(
                callback,
                jni_str!("onBytesList"),
                jni_sig!("([[B)V"),
                &[JValue::Object(&array)],
            )?;
        }
        Err(err) => {
            let kind = env.new_string(err.kind.as_str())?;
            let message = env.new_string(&err.message)?;
            env.call_method(
                callback,
                jni_str!("onError"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
                &[JValue::Object(&kind), JValue::Object(&message)],
            )?;
        }
    }
    Ok(())
}

fn jsize(len: usize) -> NativeResult<i32> {
    i32::try_from(len)
        .map_err(|_| NativeError::invalid_argument("result too large for a Java array"))
}

/// Run a native method body, turning a JNI failure or panic into a Java exception.
///
/// Operation errors never reach this point; [`start`] reports them through the
/// callback. What remains is the binding failing to talk to the JVM at all.
pub fn entry<'local>(
    mut env: EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> NativeResult<jlong>,
) -> jlong {
    env.with_env(body).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
