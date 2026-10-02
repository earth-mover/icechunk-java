use std::cell::Cell;
use std::sync::{Mutex, OnceLock, PoisonError};
use std::time::Duration;

use crate::error::{ErrorKind, NativeError, NativeResult};

/// The tokio runtime that drives icechunk's I/O.
///
/// Java threads run each operation themselves with `Handle::block_on`, so the runtime's
/// own workers only poll I/O and the tasks icechunk spawns internally. They never call
/// into Java and are never attached to the JVM, so they neither keep the JVM from
/// exiting nor pin the class loader that loaded this library.
static HANDLE: OnceLock<tokio::runtime::Handle> = OnceLock::new();

/// Owns the runtime so that `JNI_OnUnload` can stop its threads before the library's
/// code is unmapped. Only touched when the runtime starts and stops.
static OWNER: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

thread_local! {
    /// Set on the runtime's own worker and blocking-pool threads.
    static RUNTIME_THREAD: Cell<bool> = const { Cell::new(false) };
    /// Set once a Java thread has entered the runtime context.
    static ENTERED: Cell<bool> = const { Cell::new(false) };
}

/// True on the runtime's own threads, where blocking on icechunk is not allowed.
pub(crate) fn on_runtime_thread() -> bool {
    RUNTIME_THREAD.get()
}

/// Make the runtime the current one for this thread, for as long as the thread lives.
///
/// icechunk's I/O registers with whatever runtime is current. Entering the context once
/// per thread, instead of once per call, matters for speed: entering clones a reference
/// counted handle, and with several threads that clone costs about a microsecond of
/// contention per call.
pub(crate) fn enter() -> NativeResult<()> {
    if !ENTERED.get() {
        // The guard is never dropped, so the context stays entered. Dropping it would
        // have to happen on this thread in LIFO order, which nothing here can promise.
        std::mem::forget(handle()?.enter());
        ENTERED.set(true);
    }
    Ok(())
}

pub(crate) fn handle() -> NativeResult<&'static tokio::runtime::Handle> {
    if let Some(handle) = HANDLE.get() {
        return Ok(handle);
    }
    let mut owner = OWNER.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some(handle) = HANDLE.get() {
        return Ok(handle);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .thread_name("icechunk-worker")
        .on_thread_start(|| RUNTIME_THREAD.set(true))
        .enable_all()
        .build()
        .map_err(|err| {
            NativeError::new(ErrorKind::Icechunk, format!("cannot start runtime: {err}"))
        })?;
    let handle = HANDLE.get_or_init(|| runtime.handle().clone());
    *owner = Some(runtime);
    Ok(handle)
}

/// Stop the runtime's threads. Called when the JVM unloads the library.
pub(crate) fn shutdown() {
    let runtime = OWNER.lock().unwrap_or_else(PoisonError::into_inner).take();
    if let Some(runtime) = runtime {
        runtime.shutdown_timeout(Duration::from_secs(5));
    }
}
