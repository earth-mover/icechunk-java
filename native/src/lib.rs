//! JNI layer for the icechunk Java bindings.
//!
//! Every native method is a static method on `io.earthmover.icechunk.Native`. Each one
//! runs on the calling Java thread: it reads its arguments, drives the icechunk future
//! to completion on the shared tokio runtime, and returns the result or throws.
//! `dev/DESIGN.md` explains the threading, handle and memory rules.

use std::ffi::c_void;

use jni::JavaVM;
use jni::sys::{JNI_VERSION_1_8, jint};

mod buffers;
mod call;
mod codecs;
#[cfg(test)]
mod contract_tests;
mod error;
mod handles;
mod logging;
mod repository;
mod results;
mod runtime;
mod session;
mod spec;
mod storage;
mod store;

/// The pieces a downstream crate needs to add its own native methods to this library.
///
/// A crate such as an Arraylake binding depends on `icechunk-jni` as an `rlib`, defines
/// more `Java_...` exports with [`ext::run`] and [`ext::block_on`], and builds a single
/// `cdylib` containing both. Objects it registers live in the same handle table as the
/// core's, so the Java core classes can use them.
pub mod ext {
    use std::sync::Arc;

    pub use crate::call::{block_on, run, strings, text};
    pub use crate::error::{ErrorKind, NativeError, NativeResult};
    pub use crate::handles::Ref;

    use crate::handles::{self, Object};

    /// Register a repository and return the handle `NativeExtensions.repository` wraps.
    pub fn insert_repository(repository: icechunk::Repository) -> NativeResult<i64> {
        handles::insert(Object::Repository(Arc::new(repository)))
    }

    /// Register an extension's own object. Java closes it with `NativeExtensions.close`.
    pub fn insert_extension<T: Send + Sync + 'static>(object: T) -> NativeResult<i64> {
        handles::insert(Object::Extension(Arc::new(object)))
    }

    /// Look up an object registered with [`insert_extension`], borrowed for as long as
    /// the returned [`Ref`] lives.
    pub fn extension<T: Send + Sync + 'static>(handle: i64) -> NativeResult<Ref<T>> {
        handles::extension::<T>(handle)
    }

    /// The runtime icechunk's futures run on, for extensions that spawn their own tasks.
    /// It lives until the library unloads.
    pub fn runtime() -> NativeResult<&'static tokio::runtime::Handle> {
        crate::runtime::handle()
    }

    /// Parse the `RepositoryOptions` JSON the Java builder produces.
    pub fn repository_options(
        json: &str,
    ) -> NativeResult<crate::spec::RepositoryOptions> {
        crate::spec::RepositoryOptionsSpec::parse(json)
    }

    /// Register a storage and return the handle `NativeExtensions.storage` wraps.
    pub fn insert_storage(
        storage: Arc<dyn icechunk::Storage + Send + Sync>,
    ) -> NativeResult<i64> {
        handles::insert(Object::Storage(storage))
    }
}

/// The version of the contract with `Native.java`, which checks it on load; it must equal
/// `Native.ABI_VERSION`. Raise both whenever a native method's name or signature, a shared
/// constant, or a JSON document format changes.
pub(crate) const ABI_VERSION: jint = 4;

call::native! { fn abiVersion(_) -> jint {
    Ok(ABI_VERSION)
}}

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
    // registers the process-wide `JavaVM::singleton`, which dropping a global reference
    // on a non-Java thread relies on.
    let _ = unsafe { JavaVM::from_raw(vm) };
    JNI_VERSION_1_8
}

/// Called by the JVM before it unloads the library, when the class loader that loaded
/// it is collected. The runtime's threads must stop before the code they run is unmapped.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnUnload(_vm: *mut jni::sys::JavaVM, _: *mut c_void) {
    runtime::shutdown();
}
