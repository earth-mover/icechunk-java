#![allow(
    unreachable_pub,
    reason = "JNI exports are found by symbol name, not Rust paths"
)]

use icechunk::storage::{
    new_azure_blob_storage, new_gcs_storage, new_http_storage, new_in_memory_storage,
    new_local_filesystem_storage, new_s3_storage,
};
use jni::EnvUnowned;
use jni::objects::{JClass, JObject, JString};
use jni::sys::jlong;

use crate::call::{self, Reply};
use crate::error::NativeResult;
use crate::handles::{self, Object, StorageRef};
use crate::spec::StorageSpec;

async fn open(spec: StorageSpec) -> NativeResult<StorageRef> {
    let storage = match spec {
        StorageSpec::InMemory => new_in_memory_storage().await?,
        StorageSpec::LocalFilesystem { path } => {
            new_local_filesystem_storage(&path).await?
        }
        StorageSpec::S3 { bucket, prefix, options, credentials } => {
            let options = options.into_options(&credentials);
            new_s3_storage(
                options,
                bucket,
                prefix,
                Some(credentials.into()),
                Vec::new(),
                Vec::new(),
                None,
            )?
        }
        StorageSpec::Gcs { bucket, prefix, credentials, config } => new_gcs_storage(
            bucket,
            prefix,
            Some(credentials.into()),
            Some(config),
            Vec::new(),
            Vec::new(),
        )?,
        StorageSpec::Azure { account, container, prefix, credentials, config } => {
            new_azure_blob_storage(
                account,
                container,
                prefix,
                Some(credentials.into()),
                Some(config),
            )
            .await?
        }
        StorageSpec::Http { url, config } => new_http_storage(&url, Some(config), None)?,
    };
    Ok(storage)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storageOpen<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    spec: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, runtime| {
            let spec: StorageSpec = serde_json::from_str(&spec.try_to_string(env)?)?;
            let runtime = std::sync::Arc::clone(runtime);
            Ok(async move {
                let storage = open(spec).await?;
                Ok(Reply::Long(handles::insert(Object::Storage(storage), runtime)?))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_close<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    handles::remove(handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_cancel<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    task: jlong,
) {
    call::cancel(task);
}
