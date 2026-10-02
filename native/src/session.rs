use std::sync::Arc;

use icechunk::Store;
use jni::objects::JString;
use jni::sys::{jboolean, jlong};

use crate::call::{block_on, json_string, native, optional_string, text};
use crate::handles::{self, Object};
use crate::results::DiffResult;
use crate::spec::CommitSpec;

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

native! { fn sessionCommit(
    env, session: jlong, message: JString<'l>, options: JString<'l>
) -> JString<'l> {
    let session = handles::session(session)?;
    let message = text(env, &message)?;
    let CommitSpec { metadata, allow_empty } = CommitSpec::parse(&text(env, &options)?)?;
    let id = block_on(async {
        let mut session = session.write().await;
        let mut commit = session.commit(message).allow_empty(allow_empty);
        if let Some(metadata) = metadata {
            commit = commit.properties(metadata);
        }
        commit.execute().await
    })??;
    Ok(env.new_string(id.to_string())?)
}}

// A `DiffResult` document for the uncommitted changes.
native! { fn sessionStatus(env, session: jlong) -> JString<'l> {
    let session = handles::session(session)?;
    let diff = block_on(async { session.read().await.status().await })??;
    json_string(env, &DiffResult::from(&diff))
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
