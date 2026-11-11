//! Native methods for `Session`.

use std::sync::Arc;

use icechunk::Store;
use icechunk::format::Path;
use jni::objects::JString;
use jni::sys::{jboolean, jint, jlong};

use crate::call::{block_on, json_string, native, optional_string, text};
use crate::error::{NativeError, NativeResult};
use crate::handles::{self, Object};
use crate::results::DiffResult;
use crate::spec::{CommitSpec, ConflictSolverSpec};

/// What `sessionCommit` makes of the changes. The values match the `Native.COMMIT`,
/// `Native.AMEND` and `Native.FLUSH` constants.
pub(crate) const COMMIT: jint = 0;
/// Replace the branch tip's snapshot instead of adding a child to it.
pub(crate) const AMEND: jint = 1;
/// Write a snapshot without moving the branch.
pub(crate) const FLUSH: jint = 2;

/// icechunk-python's default for `rebase_tries`.
const DEFAULT_REBASE_TRIES: u16 = 1000;

fn node_path(path: &str) -> NativeResult<Path> {
    Path::try_from(path).map_err(|err| {
        NativeError::invalid_argument(format!("invalid node path {path:?}: {err}"))
    })
}

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
    env, session: jlong, message: JString<'l>, options: JString<'l>, kind: jint
) -> JString<'l> {
    let session = handles::session(session)?;
    let message = text(env, &message)?;
    let CommitSpec { metadata, allow_empty, rebase_with, rebase_tries } =
        CommitSpec::parse(&text(env, &options)?)?;
    if !matches!(kind, COMMIT | AMEND | FLUSH) {
        return Err(NativeError::invalid_argument(format!("unknown commit kind {kind}")));
    }
    let solver = rebase_with.map(ConflictSolverSpec::solver);
    let id = block_on(async {
        let mut session = session.write().await;
        let mut commit = session.commit(message).allow_empty(allow_empty);
        if let Some(metadata) = metadata {
            commit = commit.properties(metadata);
        }
        match kind {
            AMEND => commit = commit.amend(),
            FLUSH => commit = commit.anonymous(),
            _ => {}
        }
        if let Some(solver) = solver.as_deref() {
            commit = commit.rebase(solver, rebase_tries.unwrap_or(DEFAULT_REBASE_TRIES));
        }
        commit.execute().await
    })??;
    Ok(env.new_string(id.to_string())?)
}}

native! { fn sessionRebase(env, session: jlong, solver: JString<'l>) -> () {
    let session = handles::session(session)?;
    let solver = ConflictSolverSpec::parse(&text(env, &solver)?)?.solver();
    Ok(block_on(async { session.write().await.rebase(solver.as_ref()).await })??)
}}

native! { fn sessionMove(env, session: jlong, from: JString<'l>, to: JString<'l>) -> () {
    let session = handles::session(session)?;
    let from = node_path(&text(env, &from)?)?;
    let to = node_path(&text(env, &to)?)?;
    Ok(block_on(async { session.write().await.move_node(from, to).await })??)
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
