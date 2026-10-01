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
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jlong};
use jni::{Env, EnvUnowned};

use crate::call::{self, Reply};
use crate::error::{ErrorKind, NativeError, NativeResult};
use crate::handles::{self, Object};
use crate::spec::{RepositoryOptions, RepositoryOptionsSpec};

/// How `repositoryOpen` treats an existing or missing repository. The values match the
/// `Native.OPEN_*` constants.
const OPEN: jint = 0;
const CREATE: jint = 1;
const OPEN_OR_CREATE: jint = 2;

/// How `repositoryReadonlySession` interprets its version argument. The values match
/// the `Native.VERSION_*` constants.
const VERSION_BRANCH: jint = 0;
const VERSION_TAG: jint = 1;
const VERSION_SNAPSHOT: jint = 2;

pub fn text(env: &Env<'_>, value: &JString<'_>) -> NativeResult<String> {
    if value.is_null() {
        return Err(NativeError::invalid_argument("unexpected null string"));
    }
    Ok(value.try_to_string(env)?)
}

fn snapshot_id(id: &str) -> NativeResult<SnapshotId> {
    SnapshotId::try_from(id).map_err(|err| {
        NativeError::invalid_argument(format!("bad snapshot id {id:?}: {err}"))
    })
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
    class: JClass<'l>,
    callback: JObject<'l>,
    storage: jlong,
    mode: jint,
    options: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, runtime| {
            let storage = handles::storage(storage)?;
            let options = RepositoryOptionsSpec::parse(&text(env, &options)?)?;
            let runtime = Arc::clone(runtime);
            Ok(async move {
                let repository = open(storage, mode, options).await?;
                let handle =
                    handles::insert(Object::Repository(Arc::new(repository)), runtime)?;
                Ok(Reply::Long(handle))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryExists<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    storage: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let storage = handles::storage(storage)?;
            Ok(async move { Ok(Reply::Bool(Repository::exists(storage, None).await?)) })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryConfig<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let repository = handles::repository(repository)?;
            Ok(async move {
                let json = serde_json::to_string(repository.config()).map_err(|err| {
                    NativeError::new(ErrorKind::Icechunk, err.to_string())
                })?;
                Ok(Reply::Text(Some(json)))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryListBranches<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let repository = handles::repository(repository)?;
            Ok(async move {
                Ok(Reply::Texts(repository.list_branches().await?.into_iter().collect()))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryListTags<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let repository = handles::repository(repository)?;
            Ok(async move {
                Ok(Reply::Texts(repository.list_tags().await?.into_iter().collect()))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryLookupBranch<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            Ok(async move {
                let id = repository.lookup_branch(&name).await?;
                Ok(Reply::Text(Some(id.to_string())))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryLookupTag<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            Ok(async move {
                let id = repository.lookup_tag(&name).await?;
                Ok(Reply::Text(Some(id.to_string())))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryCreateBranch<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
    snapshot: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            let snapshot = snapshot_id(&text(env, &snapshot)?)?;
            Ok(async move {
                repository.create_branch(&name, &snapshot).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryDeleteBranch<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            Ok(async move {
                repository.delete_branch(&name).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryResetBranch<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
    snapshot: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            let snapshot = snapshot_id(&text(env, &snapshot)?)?;
            Ok(async move {
                repository.reset_branch(&name, &snapshot, None).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryCreateTag<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
    snapshot: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            let snapshot = snapshot_id(&text(env, &snapshot)?)?;
            Ok(async move {
                repository.create_tag(&name, &snapshot).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryDeleteTag<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    name: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let name = text(env, &name)?;
            Ok(async move {
                repository.delete_tag(&name).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryReadonlySession<
    'l,
>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    kind: jint,
    value: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, runtime| {
            let repository = handles::repository(repository)?;
            let version = version(kind, text(env, &value)?)?;
            let runtime = Arc::clone(runtime);
            Ok(async move {
                let session = repository.readonly_session(&version).await?;
                let session = Arc::new(tokio::sync::RwLock::new(session));
                Ok(Reply::Long(handles::insert(Object::Session(session), runtime)?))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryWritableSession<
    'l,
>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    branch: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, runtime| {
            let repository = handles::repository(repository)?;
            let branch = text(env, &branch)?;
            let runtime = Arc::clone(runtime);
            Ok(async move {
                let session = repository.writable_session(&branch).await?;
                let session = Arc::new(tokio::sync::RwLock::new(session));
                Ok(Reply::Long(handles::insert(Object::Session(session), runtime)?))
            })
        })
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

/// Reply with four strings per snapshot, newest first: id, parent id (empty for the
/// first snapshot), commit time in RFC 3339 UTC, and message. `Repository.ancestry`
/// reads them in that order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_repositoryAncestry<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    repository: jlong,
    kind: jint,
    value: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let repository = handles::repository(repository)?;
            let version = version(kind, text(env, &value)?)?;
            Ok(async move {
                let snapshots: Vec<_> =
                    repository.ancestry(&version).await?.try_collect().await?;
                let mut fields = Vec::with_capacity(snapshots.len() * 4);
                for snapshot in snapshots {
                    fields.push(snapshot.id.to_string());
                    fields.push(
                        snapshot.parent_id.map(|id| id.to_string()).unwrap_or_default(),
                    );
                    fields.push(
                        snapshot.flushed_at.to_rfc3339_opts(SecondsFormat::Micros, true),
                    );
                    fields.push(snapshot.message);
                }
                Ok(Reply::Texts(fields))
            })
        })
    })
}
