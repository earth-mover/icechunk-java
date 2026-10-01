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
