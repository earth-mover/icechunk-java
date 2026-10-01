#![allow(
    unreachable_pub,
    reason = "JNI exports are found by symbol name, not Rust paths"
)]

use std::sync::Arc;

use icechunk::Store;
use jni::EnvUnowned;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};

use crate::call::{self, block_on, optional_string, text};
use crate::handles::{self, Object};

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionSnapshotId<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
) -> JString<'l> {
    call::run(env, |env| {
        let session = handles::session(session)?;
        let id = block_on(async { session.read().await.snapshot_id().to_string() })?;
        Ok(env.new_string(id)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionBranch<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
) -> JString<'l> {
    call::run(env, |env| {
        let session = handles::session(session)?;
        let branch =
            block_on(async { session.read().await.branch().map(str::to_owned) })?;
        optional_string(env, branch.as_deref())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionReadOnly<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
) -> jboolean {
    call::run(env, |_| {
        let session = handles::session(session)?;
        block_on(async { session.read().await.read_only() })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionHasUncommittedChanges<
    'l,
>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
) -> jboolean {
    call::run(env, |_| {
        let session = handles::session(session)?;
        block_on(async { session.read().await.has_uncommitted_changes() })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionCommit<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
    message: JString<'l>,
) -> JString<'l> {
    call::run(env, |env| {
        let session = handles::session(session)?;
        let message = text(env, &message)?;
        let id =
            block_on(async { session.write().await.commit(message).execute().await })??;
        Ok(env.new_string(id.to_string())?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionDiscardChanges<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
) {
    call::run(env, |_| {
        let session = handles::session(session)?;
        Ok(block_on(async { session.write().await.discard_changes() })??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionStore<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    session: jlong,
) -> jlong {
    call::run(env, |_| {
        let session = handles::session(session)?;
        // `from_session` takes `get_partial_values_concurrency` from the repository config.
        let store = block_on(Store::from_session(session))?;
        handles::insert(Object::Store(Arc::new(store)))
    })
}
