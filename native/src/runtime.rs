use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, PoisonError, Weak};

use jni::objects::{JClass, JClassLoader, JValue};
use jni::{Env, JavaVM, jni_sig, jni_str};

use crate::error::{ErrorKind, NativeError, NativeResult};

/// The tokio runtime shared by every live handle and in-flight task.
///
/// Handles and tasks each hold an `Arc<Runtime>`; the cache holds only a `Weak`. When
/// the last owner goes away the runtime shuts down and its threads detach from the JVM,
/// so they stop pinning the class loader that loaded this library. The next call after
/// that builds a fresh runtime.
#[derive(Debug)]
pub struct Runtime {
    inner: Option<tokio::runtime::Runtime>,
}

static SHARED: Mutex<Weak<Runtime>> = Mutex::new(Weak::new());

impl Runtime {
    pub(crate) fn handle(&self) -> NativeResult<&tokio::runtime::Handle> {
        self.inner
            .as_ref()
            .map(tokio::runtime::Runtime::handle)
            .ok_or_else(|| NativeError::new(ErrorKind::Closed, "runtime is shut down"))
    }
}

impl Drop for Runtime {
    fn drop(&mut self) {
        // The last owner may be a task finishing on one of this runtime's own workers,
        // so shutdown must not wait for the workers to stop.
        if let Some(runtime) = self.inner.take() {
            runtime.shutdown_background();
        }
    }
}

/// Return the shared runtime, building it if no live owner holds one.
///
/// `anchor` is any class loaded by the binding's class loader, normally the `Native`
/// class the calling native method is declared on.
pub(crate) fn shared(
    env: &mut Env<'_>,
    anchor: &JClass<'_>,
) -> NativeResult<Arc<Runtime>> {
    let mut cached = SHARED.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some(runtime) = cached.upgrade() {
        return Ok(runtime);
    }
    let runtime = Arc::new(build(env, anchor)?);
    *cached = Arc::downgrade(&runtime);
    Ok(runtime)
}

/// True when the current thread is a worker of some tokio runtime.
///
/// Waiting on an icechunk result from such a thread can starve the runtime that has to
/// produce the result, so callers reject the request instead.
pub(crate) fn on_runtime_thread() -> bool {
    tokio::runtime::Handle::try_current().is_ok()
}

fn build(env: &mut Env<'_>, anchor: &JClass<'_>) -> NativeResult<Runtime> {
    let loader = anchor.get_class_loader(env)?;
    let loader = env.new_global_ref(loader)?;
    let counter = AtomicUsize::new(0);
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .thread_name_fn(move || {
            format!("icechunk-worker-{}", counter.fetch_add(1, Ordering::Relaxed))
        })
        .on_thread_start(move || {
            if let Ok(vm) = JavaVM::singleton() {
                // An attach failure is not fatal: the result callback attaches the
                // thread again, it just lacks the context class loader.
                let _ = vm.attach_current_thread(|env| -> NativeResult<()> {
                    init_java_thread(env, &loader)
                });
            }
        })
        .on_thread_stop(|| {
            // Detach explicitly: relying on thread-exit detachment can deadlock on
            // Windows (jni-rs/jni-rs#701).
            if let Ok(vm) = JavaVM::singleton() {
                let _ = vm.detach_current_thread();
            }
        })
        .enable_all()
        .build()
        .map_err(|err| {
            NativeError::new(ErrorKind::Icechunk, format!("cannot start runtime: {err}"))
        })?;
    Ok(Runtime { inner: Some(runtime) })
}

/// Give a freshly attached worker the binding's context class loader and its Rust name.
fn init_java_thread(env: &mut Env<'_>, loader: &JClassLoader<'_>) -> NativeResult<()> {
    let thread = env
        .call_static_method(
            jni_str!("java/lang/Thread"),
            jni_str!("currentThread"),
            jni_sig!("()Ljava/lang/Thread;"),
            &[],
        )?
        .l()?;
    env.call_method(
        &thread,
        jni_str!("setContextClassLoader"),
        jni_sig!("(Ljava/lang/ClassLoader;)V"),
        &[JValue::Object(loader)],
    )?;
    if let Some(name) = std::thread::current().name() {
        let name = env.new_string(name)?;
        env.call_method(
            &thread,
            jni_str!("setName"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(&name)],
        )?;
    }
    Ok(())
}
