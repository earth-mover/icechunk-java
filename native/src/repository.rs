use std::sync::Arc;

use futures::TryStreamExt as _;
use icechunk::Repository;
use icechunk::ops::gc::{ExpiredRefAction, expire, garbage_collect};
use jni::objects::{JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong};

use crate::call::{block_on, json_string, native, optional_text, strings, text};
use crate::error::{NativeError, NativeResult};
use crate::handles::{self, Object};
use crate::results::{
    DiffResult, ExpirationResult, GcSummaryResult, SnapshotInfoResult,
    SnapshotInfosResult,
};
use crate::spec::{
    ExpireSpec, GcSpec, RepositoryOptions, RepositoryOptionsSpec, VersionSpec,
    snapshot_id,
};

/// How `repositoryOpen` treats an existing or missing repository. The values match the
/// `Native.OPEN_*` constants.
pub(crate) const OPEN: jint = 0;
pub(crate) const CREATE: jint = 1;
pub(crate) const OPEN_OR_CREATE: jint = 2;

async fn open(
    storage: handles::StorageRef,
    mode: jint,
    options: RepositoryOptions,
) -> NativeResult<Repository> {
    let RepositoryOptions {
        config,
        virtual_chunk_credentials,
        spec_version,
        check_clean_root,
    } = options;
    let repository = match mode {
        OPEN => Repository::open(config, storage, virtual_chunk_credentials).await?,
        CREATE => {
            Repository::create(
                config,
                storage,
                virtual_chunk_credentials,
                spec_version,
                check_clean_root,
            )
            .await?
        }
        OPEN_OR_CREATE => {
            Repository::open_or_create(
                config,
                storage,
                virtual_chunk_credentials,
                spec_version,
                check_clean_root,
            )
            .await?
        }
        other => {
            return Err(NativeError::invalid_argument(format!(
                "unknown open mode {other}"
            )));
        }
    };
    Ok(repository)
}

native! { fn repositoryOpen(env, storage: jlong, mode: jint, options: JString<'l>) -> jlong {
    let storage = handles::storage(storage)?;
    let options = RepositoryOptionsSpec::parse(&text(env, &options)?)?;
    let repository = block_on(open(Arc::clone(&storage), mode, options))??;
    handles::insert(Object::Repository(Arc::new(repository)))
}}

native! { fn repositoryExists(_, storage: jlong) -> jboolean {
    let storage = handles::storage(storage)?;
    Ok(block_on(Repository::exists(Arc::clone(&storage), None))??)
}}

native! { fn repositoryConfig(env, repository: jlong) -> JString<'l> {
    let repository = handles::repository(repository)?;
    json_string(env, repository.config())
}}

native! { fn repositoryListBranches(env, repository: jlong) -> JObjectArray<'l, JString<'l>> {
    let repository = handles::repository(repository)?;
    let names = block_on(repository.list_branches())??;
    strings(env, names.iter())
}}

native! { fn repositoryListTags(env, repository: jlong) -> JObjectArray<'l, JString<'l>> {
    let repository = handles::repository(repository)?;
    let names = block_on(repository.list_tags())??;
    strings(env, names.iter())
}}

native! { fn repositoryLookupBranch(env, repository: jlong, name: JString<'l>) -> JString<'l> {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    let id = block_on(repository.lookup_branch(&name))??;
    Ok(env.new_string(id.to_string())?)
}}

native! { fn repositoryLookupTag(env, repository: jlong, name: JString<'l>) -> JString<'l> {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    let id = block_on(repository.lookup_tag(&name))??;
    Ok(env.new_string(id.to_string())?)
}}

native! { fn repositoryCreateBranch(
    env, repository: jlong, name: JString<'l>, snapshot: JString<'l>
) -> () {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    let snapshot = snapshot_id(&text(env, &snapshot)?)?;
    Ok(block_on(repository.create_branch(&name, &snapshot))??)
}}

native! { fn repositoryDeleteBranch(env, repository: jlong, name: JString<'l>) -> () {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    Ok(block_on(repository.delete_branch(&name))??)
}}

// `from` is null, or the snapshot the branch must currently point to.
native! { fn repositoryResetBranch(
    env, repository: jlong, name: JString<'l>, to: JString<'l>, from: JString<'l>
) -> () {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    let to = snapshot_id(&text(env, &to)?)?;
    let from = optional_text(env, &from)?.map(|id| snapshot_id(&id)).transpose()?;
    Ok(block_on(repository.reset_branch(&name, &to, from.as_ref()))??)
}}

native! { fn repositoryCreateTag(
    env, repository: jlong, name: JString<'l>, snapshot: JString<'l>
) -> () {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    let snapshot = snapshot_id(&text(env, &snapshot)?)?;
    Ok(block_on(repository.create_tag(&name, &snapshot))??)
}}

native! { fn repositoryDeleteTag(env, repository: jlong, name: JString<'l>) -> () {
    let repository = handles::repository(repository)?;
    let name = text(env, &name)?;
    Ok(block_on(repository.delete_tag(&name))??)
}}

// A `SnapshotInfosResult` document, newest first.
native! { fn repositoryAncestry(env, repository: jlong, version: JString<'l>) -> JString<'l> {
    let repository = handles::repository(repository)?;
    let version = VersionSpec::parse(&text(env, &version)?)?;
    let snapshots: Vec<_> =
        block_on(async { repository.ancestry(&version).await?.try_collect().await })??;
    json_string(env, &SnapshotInfosResult(&snapshots))
}}

native! { fn repositoryLookupSnapshot(env, repository: jlong, id: JString<'l>) -> JString<'l> {
    let repository = handles::repository(repository)?;
    let id = snapshot_id(&text(env, &id)?)?;
    let snapshot = block_on(repository.lookup_snapshot(&id))??;
    json_string(env, &SnapshotInfoResult::from(&snapshot))
}}

native! { fn repositoryResolveVersion(env, repository: jlong, version: JString<'l>) -> JString<'l> {
    let repository = handles::repository(repository)?;
    let version = VersionSpec::parse(&text(env, &version)?)?;
    let id = block_on(repository.resolve_version(&version))??;
    Ok(env.new_string(id.to_string())?)
}}

native! { fn repositoryDiff(
    env, repository: jlong, from: JString<'l>, to: JString<'l>
) -> JString<'l> {
    let repository = handles::repository(repository)?;
    let from = VersionSpec::parse(&text(env, &from)?)?;
    let to = VersionSpec::parse(&text(env, &to)?)?;
    let diff = block_on(repository.diff(&from, &to))??;
    json_string(env, &DiffResult::from(&diff))
}}

native! { fn repositoryReadonlySession(env, repository: jlong, version: JString<'l>) -> jlong {
    let repository = handles::repository(repository)?;
    let version = VersionSpec::parse(&text(env, &version)?)?;
    let session = block_on(repository.readonly_session(&version))??;
    handles::insert(Object::Session(Arc::new(tokio::sync::RwLock::new(session))))
}}

native! { fn repositoryWritableSession(env, repository: jlong, branch: JString<'l>) -> jlong {
    let repository = handles::repository(repository)?;
    let branch = text(env, &branch)?;
    let session = block_on(repository.writable_session(&branch))??;
    handles::insert(Object::Session(Arc::new(tokio::sync::RwLock::new(session))))
}}

fn ref_action(delete: bool) -> ExpiredRefAction {
    if delete { ExpiredRefAction::Delete } else { ExpiredRefAction::Ignore }
}

native! { fn repositoryExpireSnapshots(env, repository: jlong, options: JString<'l>) -> JString<'l> {
    // A long call must not hold the handle's epoch guard; see `handles`.
    let repository = Arc::clone(&*handles::repository(repository)?);
    let spec = ExpireSpec::parse(&text(env, &options)?)?;
    let config = repository.config();
    let result = block_on(expire(
        Arc::clone(repository.asset_manager()),
        spec.older_than,
        ref_action(spec.delete_expired_branches),
        ref_action(spec.delete_expired_tags),
        Some(config.repo_update_retries()),
        config.num_updates_per_repo_info_file(),
    ))??;
    json_string(env, &ExpirationResult::from(&result))
}}

native! { fn repositoryGarbageCollect(env, repository: jlong, options: JString<'l>) -> JString<'l> {
    // A long call must not hold the handle's epoch guard; see `handles`.
    let repository = Arc::clone(&*handles::repository(repository)?);
    let gc = GcSpec::parse(&text(env, &options)?)?;
    let config = repository.config();
    let summary = block_on(garbage_collect(
        Arc::clone(repository.asset_manager()),
        &gc,
        Some(config.repo_update_retries()),
        config.num_updates_per_repo_info_file(),
    ))??;
    json_string(env, &GcSummaryResult::from(&summary))
}}
