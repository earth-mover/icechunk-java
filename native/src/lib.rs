//! JNI layer for the icechunk Java bindings.
//!
//! Every native method is a static method on `io.earthmover.icechunk.Native`. Each one
//! takes a `NativeCall` callback object, starts the operation on the shared tokio
//! runtime, and returns a task id. The result reaches Java through the callback, never
//! through the return value, so no native method blocks and no Java exception is built
//! on a runtime thread. `DESIGN.md` at the repository root explains the threading,
//! handle and lifetime rules.

use std::ffi::c_void;

use jni::JavaVM;
use jni::sys::{JNI_VERSION_1_8, jint};

mod call;
mod error;
mod handles;
mod repository;
mod runtime;
mod session;
mod spec;
mod storage;
mod store;

/// The pieces a downstream crate needs to add its own native methods to this library.
///
/// A crate such as an Arraylake binding depends on `icechunk-jni` as an `rlib`, defines
/// more `Java_...` exports with [`ext::entry`] and [`ext::start`], and builds a single
/// `cdylib` containing both. Objects it registers live in the same handle table as the
/// core's, so the Java core classes can use them.
pub mod ext {
    use std::sync::Arc;

    pub use crate::call::{Reply, entry, start};
    pub use crate::error::{ErrorKind, NativeError, NativeResult};
    pub use crate::repository::text;
    pub use crate::runtime::Runtime;

    use crate::handles::{self, Object};

    /// Register a repository and return the handle `NativeExtensions.repository` wraps.
    pub fn insert_repository(
        repository: icechunk::Repository,
        runtime: Arc<Runtime>,
    ) -> NativeResult<i64> {
        handles::insert(Object::Repository(Arc::new(repository)), runtime)
    }

    /// Register a storage and return the handle `NativeExtensions.storage` wraps.
    pub fn insert_storage(
        storage: Arc<dyn icechunk::Storage + Send + Sync>,
        runtime: Arc<Runtime>,
    ) -> NativeResult<i64> {
        handles::insert(Object::Storage(storage), runtime)
    }
}

/// Called by the JVM when the library is loaded.
///
/// # Safety
///
/// `vm` must be the `JavaVM` pointer the JVM passes to `JNI_OnLoad`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM,
    _: *mut c_void,
) -> jint {
    // SAFETY: the JVM guarantees `vm` is valid for the life of the library. Wrapping it
    // registers the process-wide `JavaVM::singleton` that runtime threads attach through.
    let _ = unsafe { JavaVM::from_raw(vm) };
    JNI_VERSION_1_8
}
