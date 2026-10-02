use std::sync::Arc;

use icechunk::Store;
use jni::objects::JString;
use jni::sys::{jboolean, jlong};

use crate::call::{block_on, native, optional_string, text};
use crate::handles::{self, Object};

native! { fn sessionSnapshotId(env, session: jlong) -> JString<'l> {
    let session = handles::session(session)?;
    let id = block_on(async { session.read().await.snapshot_id().to_string() })?;
    Ok(env.new_string(id)?)
}}

native! { fn sessionBranch(env, session: jlong) -> JString<'l> {
    let session = handles::session(session)?;
    let branch = block_on(async { session.read().await.branch().map(str::to_owned) })?;
    optional_string(env, branch.as_deref())
}}

native! { fn sessionReadOnly(_, session: jlong) -> jboolean {
    let session = handles::session(session)?;
    block_on(async { session.read().await.read_only() })
}}

native! { fn sessionHasUncommittedChanges(_, session: jlong) -> jboolean {
    let session = handles::session(session)?;
    block_on(async { session.read().await.has_uncommitted_changes() })
}}

native! { fn sessionCommit(env, session: jlong, message: JString<'l>) -> JString<'l> {
    let session = handles::session(session)?;
    let message = text(env, &message)?;
    let id = block_on(async { session.write().await.commit(message).execute().await })??;
    Ok(env.new_string(id.to_string())?)
}}

native! { fn sessionDiscardChanges(_, session: jlong) -> () {
    let session = handles::session(session)?;
    Ok(block_on(async { session.write().await.discard_changes() })??)
}}

native! { fn sessionStore(_, session: jlong) -> jlong {
    let session = handles::session(session)?;
    // `from_session` takes `get_partial_values_concurrency` from the repository config.
    let store = block_on(Store::from_session(Arc::clone(&session)))?;
    handles::insert(Object::Store(Arc::new(store)))
}}
