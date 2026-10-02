//! Native methods for `Storage`: opening each kind of storage from its JSON spec, and
//! `Native.close`, which closes a handle of any type.

use icechunk::storage::{
    new_azure_blob_storage, new_gcs_storage, new_http_storage, new_in_memory_storage,
    new_local_filesystem_storage, new_s3_storage,
};
use jni::objects::JString;
use jni::sys::jlong;

use crate::call::{block_on, native, text};
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

native! { fn storageOpen(env, spec: JString<'l>) -> jlong {
    let spec: StorageSpec = serde_json::from_str(&text(env, &spec)?)?;
    let storage = block_on(open(spec))??;
    handles::insert(Object::Storage(storage))
}}

native! { fn close(_, handle: jlong) -> () {
    handles::remove(handle);
    Ok(())
}}
