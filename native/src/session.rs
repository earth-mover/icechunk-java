#![allow(
    unreachable_pub,
    reason = "JNI exports are found by symbol name, not Rust paths"
)]

use std::sync::Arc;

use icechunk::Store;
use jni::EnvUnowned;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jlong};

use crate::call::{self, Reply};
use crate::error::NativeError;
use crate::handles::{self, Object};
use crate::repository::text;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionSnapshotId<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let session = handles::session(session)?;
            Ok(async move {
                Ok(Reply::Text(Some(session.read().await.snapshot_id().to_string())))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionBranch<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let session = handles::session(session)?;
            Ok(async move {
                Ok(Reply::Text(session.read().await.branch().map(str::to_owned)))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionReadOnly<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let session = handles::session(session)?;
            Ok(async move { Ok(Reply::Bool(session.read().await.read_only())) })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionHasUncommittedChanges<
    'l,
>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let session = handles::session(session)?;
            Ok(
                async move { Ok(Reply::Bool(session.read().await.has_uncommitted_changes())) },
            )
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionCommit<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
    message: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let session = handles::session(session)?;
            let message = text(env, &message)?;
            Ok(async move {
                let mut session = session.write().await;
                let id = session.commit(message).execute().await?;
                Ok(Reply::Text(Some(id.to_string())))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionDiscardChanges<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let session = handles::session(session)?;
            Ok(async move {
                session.write().await.discard_changes()?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_sessionStore<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    session: jlong,
    concurrency: jint,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, runtime| {
            let session = handles::session(session)?;
            let concurrency =
                u16::try_from(concurrency).ok().filter(|c| *c > 0).ok_or_else(|| {
                    NativeError::invalid_argument(format!(
                        "concurrency must be between 1 and {}, got {concurrency}",
                        u16::MAX
                    ))
                })?;
            let runtime = Arc::clone(runtime);
            Ok(async move {
                let store = Store::from_session_and_config(session, concurrency);
                Ok(Reply::Long(handles::insert(Object::Store(Arc::new(store)), runtime)?))
            })
        })
    })
}
