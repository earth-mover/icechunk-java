//! Running operations for native methods.
//!
//! A native method runs on the calling Java thread from start to finish: it reads its
//! arguments, drives the icechunk future to completion with [`block_on`], and converts
//! the result to a Java value. Errors become Java exceptions through [`ThrowIcechunk`],
//! thrown before the method returns.

use std::future::Future;

use jni::errors::ErrorPolicy;
use jni::objects::{JObject, JObjectArray, JString, JThrowable, JValue};
use jni::strings::JNIString;
use jni::{Env, EnvUnowned, jni_sig, jni_str};

use crate::error::{ErrorKind, NativeError, NativeResult};
use crate::runtime;

/// Run `future` to completion on the calling thread.
///
/// Fails instead of blocking when the caller is itself a runtime thread: blocking there
/// would hold up the threads that have to drive the future.
pub fn block_on<F: Future>(future: F) -> NativeResult<F::Output> {
    if runtime::on_runtime_thread() {
        return Err(NativeError::new(
            ErrorKind::RuntimeThread,
            "icechunk was called from one of its own runtime threads",
        ));
    }
    runtime::enter()?;
    // The future runs on this thread; the runtime's workers drive the I/O it waits on.
    // `futures`' executor parks this thread without touching memory shared with other
    // callers, unlike tokio's `Handle::block_on`.
    Ok(futures::executor::block_on(future))
}

/// Run a native method body, turning an error or panic into a pending Java exception.
pub fn run<'local, T, F>(mut env: EnvUnowned<'local>, body: F) -> T
where
    T: Default,
    F: FnOnce(&mut Env<'local>) -> NativeResult<T>,
{
    env.with_env(body).resolve::<ThrowIcechunk>()
}

/// Read a Java string argument, rejecting null.
pub fn text(env: &Env<'_>, value: &JString<'_>) -> NativeResult<String> {
    if value.is_null() {
        return Err(NativeError::invalid_argument("unexpected null string"));
    }
    Ok(value.try_to_string(env)?)
}

/// Throws an `IcechunkException` (or the subclass `IcechunkException.fromNative` picks
/// for the error kind) for an error, and a `RuntimeException` for a panic.
#[derive(Debug)]
pub enum ThrowIcechunk {}

impl<T: Default> ErrorPolicy<T, NativeError> for ThrowIcechunk {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        err: NativeError,
    ) -> jni::errors::Result<T> {
        // A JNI error usually means a Java exception is already pending; let it through.
        if !env.exception_check() {
            let exception = to_exception(env, &err)?;
            let _ = env.throw(exception);
        }
        Ok(T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        payload: Box<dyn std::any::Any + Send + 'static>,
    ) -> jni::errors::Result<T> {
        if !env.exception_check() {
            let message = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_owned())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "non-string panic payload".to_owned());
            let _ = env.throw_new(
                jni_str!("java/lang/RuntimeException"),
                JNIString::new(format!("icechunk native code panicked: {message}")),
            );
        }
        Ok(T::default())
    }
}

fn to_exception<'local>(
    env: &mut Env<'local>,
    err: &NativeError,
) -> jni::errors::Result<JThrowable<'local>> {
    let kind = env.new_string(err.kind.as_str())?;
    let message = env.new_string(&err.message)?;
    let exception = env
        .call_static_method(
            jni_str!("io/earthmover/icechunk/IcechunkException"),
            jni_str!("fromNative"),
            jni_sig!(
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/RuntimeException;"
            ),
            &[JValue::Object(&kind), JValue::Object(&message)],
        )?
        .l()?;
    // SAFETY: `fromNative` is declared to return a `RuntimeException`, a `Throwable`.
    Ok(unsafe { JThrowable::from_raw(env, exception.into_raw()) })
}

/// A Java `String[]`.
pub fn strings<'local>(
    env: &mut Env<'local>,
    values: impl ExactSizeIterator<Item = impl AsRef<str>>,
) -> NativeResult<JObjectArray<'local>> {
    let len = jsize(values.len())?;
    let array =
        env.new_object_array(len, jni_str!("java/lang/String"), JObject::null())?;
    for (index, value) in values.enumerate() {
        let value = env.new_string(value)?;
        array.set_element(env, index, &value)?;
        // Delete each element's local reference as we go; a listing can have more
        // entries than the JNI frame's local reference capacity.
        env.delete_local_ref(value);
    }
    Ok(array)
}

/// A Java `String`, or null.
pub fn optional_string<'local>(
    env: &mut Env<'local>,
    value: Option<&str>,
) -> NativeResult<JString<'local>> {
    match value {
        Some(value) => Ok(env.new_string(value)?),
        None => Ok(JString::null()),
    }
}

pub(crate) fn jsize(len: usize) -> NativeResult<i32> {
    i32::try_from(len)
        .map_err(|_| NativeError::invalid_argument("result too large for a Java array"))
}
