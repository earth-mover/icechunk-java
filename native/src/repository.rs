#![allow(
    unreachable_pub,
    reason = "JNI exports are found by symbol name, not Rust paths"
)]

use std::sync::Arc;

use chrono::SecondsFormat;
use futures::TryStreamExt as _;
use icechunk::Repository;
use icechunk::format::SnapshotId;
use icechunk::format::format_constants::SpecVersionBin;
use icechunk::repository::VersionInfo;
use jni::EnvUnowned;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong};

use crate::call::{self, block_on, strings, text};
use crate::error::{ErrorKind, NativeError, NativeResult};
use crate::handles::{self, Object};
use crate::spec::{RepositoryOptions, RepositoryOptionsSpec};

/// How `repositoryOpen` treats an existing or missing repository. The values match the
/// `Native.OPEN_*` constants.
const OPEN: jint = 0;
const CREATE: jint = 1;
const OPEN_OR_CREATE: jint = 2;

/// How a version argument is interpreted. The values match the `Native.VERSION_*`
/// constants.
const VERSION_BRANCH: jint = 0;
const VERSION_TAG: jint = 1;
const VERSION_SNAPSHOT: jint = 2;

fn snapshot_id(id: &str) -> NativeResult<SnapshotId> {
    SnapshotId::try_from(id).map_err(|err| {
        NativeError::invalid_argument(format!("bad snapshot id {id:?}: {err}"))
    })
}

fn version(kind: jint, value: String) -> NativeResult<VersionInfo> {
    match kind {
        VERSION_BRANCH => Ok(VersionInfo::BranchTipRef(value)),
        VERSION_TAG => Ok(VersionInfo::TagRef(value)),
        VERSION_SNAPSHOT => Ok(VersionInfo::SnapshotId(snapshot_id(&value)?)),
        other => {
            Err(NativeError::invalid_argument(format!("unknown version kind {other}")))
        }
    }
}

fn spec_version(options: &RepositoryOptions) -> NativeResult<Option<SpecVersionBin>> {
    options
        .spec_version
        .map(|v| {
            SpecVersionBin::try_from(v).map_err(|err| {
                NativeError::invalid_argument(format!(
                    "unsupported spec version {v}: {err}"
                ))
            })
        })
        .transpose()
}

async fn open(
    storage: handles::StorageRef,
    mode: jint,
    options: RepositoryOptions,
) -> NativeResult<Repository> {
    let version = spec_version(&options)?;
    let repository = match mode {
        OPEN => {
            let builder = Repository::open(storage)
                .authorize_virtual_chunk_access(options.virtual_chunk_credentials);
            let builder = match options.config {
                Some(config) => builder.config(config),
                None => builder,
            };
            builder.execute().await?
        }
        CREATE => {
            let builder = Repository::create(storage)
                .authorize_virtual_chunk_access(options.virtual_chunk_credentials)
                .check_clean_root(options.check_clean_root);
            let builder = match options.config {
                Some(config) => builder.config(config),
                None => builder,
            };
            let builder = match version {
                Some(version) => builder.spec_version(version),
                None => builder,
            };
            builder.execute().await?
        }
        OPEN_OR_CREATE => {
            let builder = Repository::open_or_create(storage)
                .authorize_virtual_chunk_access(options.virtual_chunk_credentials)
                .check_clean_root(options.check_clean_root);
            let builder = match options.config {
                Some(config) => builder.config(config),
                None => builder,
            };
            let builder = match version {
                Some(version) => builder.spec_version(version),
                None => builder,
            };
            builder.execute().await?
        }
        other => {
            return Err(NativeError::invalid_argument(format!(
                "unknown open mode {other}"
            )));
        }
    };
    Ok(repository)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryOpen<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    storage: jlong,
    mode: jint,
    options: JString<'l>,
) -> jlong {
    call::run(env, |env| {
        let storage = handles::storage(storage)?;
        let options = RepositoryOptionsSpec::parse(&text(env, &options)?)?;
        let repository = block_on(open(storage, mode, options))??;
        handles::insert(Object::Repository(Arc::new(repository)))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryExists<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    storage: jlong,
) -> jboolean {
    call::run(env, |_| {
        let storage = handles::storage(storage)?;
        Ok(block_on(Repository::exists(storage, None))??)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryConfig<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
) -> JString<'l> {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let json = serde_json::to_string(repository.config())
            .map_err(|err| NativeError::new(ErrorKind::Icechunk, err.to_string()))?;
        Ok(env.new_string(json)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryListBranches<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
) -> JObjectArray<'l> {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let names = block_on(repository.list_branches())??;
        strings(env, names.iter())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryListTags<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
) -> JObjectArray<'l> {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let names = block_on(repository.list_tags())??;
        strings(env, names.iter())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryLookupBranch<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
) -> JString<'l> {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        let id = block_on(repository.lookup_branch(&name))??;
        Ok(env.new_string(id.to_string())?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryLookupTag<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
) -> JString<'l> {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        let id = block_on(repository.lookup_tag(&name))??;
        Ok(env.new_string(id.to_string())?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryCreateBranch<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
    snapshot: JString<'l>,
) {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        let snapshot = snapshot_id(&text(env, &snapshot)?)?;
        Ok(block_on(repository.create_branch(&name, &snapshot))??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryDeleteBranch<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
) {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        Ok(block_on(repository.delete_branch(&name))??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryResetBranch<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
    snapshot: JString<'l>,
) {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        let snapshot = snapshot_id(&text(env, &snapshot)?)?;
        Ok(block_on(repository.reset_branch(&name, &snapshot, None))??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryCreateTag<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
    snapshot: JString<'l>,
) {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        let snapshot = snapshot_id(&text(env, &snapshot)?)?;
        Ok(block_on(repository.create_tag(&name, &snapshot))??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryDeleteTag<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    name: JString<'l>,
) {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let name = text(env, &name)?;
        Ok(block_on(repository.delete_tag(&name))??)
    });
}

/// Four strings per snapshot, newest first: id, parent id (empty for the first
/// snapshot), commit time in RFC 3339 UTC, and message. `Repository.ancestry` reads them
/// in that order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryAncestry<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    kind: jint,
    value: JString<'l>,
) -> JObjectArray<'l> {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let version = version(kind, text(env, &value)?)?;
        let snapshots: Vec<_> = block_on(async {
            repository.ancestry(&version).await?.try_collect().await
        })??;
        let mut fields = Vec::with_capacity(snapshots.len() * 4);
        for snapshot in snapshots {
            fields.push(snapshot.id.to_string());
            fields.push(snapshot.parent_id.map(|id| id.to_string()).unwrap_or_default());
            fields.push(snapshot.flushed_at.to_rfc3339_opts(SecondsFormat::Micros, true));
            fields.push(snapshot.message);
        }
        strings(env, fields.iter())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryReadonlySession<
    'l,
>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    kind: jint,
    value: JString<'l>,
) -> jlong {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let version = version(kind, text(env, &value)?)?;
        let session = block_on(repository.readonly_session(&version))??;
        handles::insert(Object::Session(Arc::new(tokio::sync::RwLock::new(session))))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryWritableSession<
    'l,
>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    repository: jlong,
    branch: JString<'l>,
) -> jlong {
    call::run(env, |env| {
        let repository = handles::repository(repository)?;
        let branch = text(env, &branch)?;
        let session = block_on(repository.writable_session(&branch))??;
        handles::insert(Object::Session(Arc::new(tokio::sync::RwLock::new(session))))
    })
}
