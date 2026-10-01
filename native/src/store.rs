#![allow(
    unreachable_pub,
    reason = "JNI exports are found by symbol name, not Rust paths"
)]

use bytes::Bytes;
use futures::TryStreamExt as _;
use icechunk::format::ByteRange;
use icechunk::store::{StoreError, StoreErrorKind};
use jni::EnvUnowned;
use jni::objects::{JByteArray, JClass, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong};

use crate::call::{self, Reply};
use crate::error::{NativeError, NativeResult};
use crate::handles;
use crate::repository::text;

/// Byte range kinds; the values match the `Native.RANGE_*` constants. Each range
/// travels as a `(kind, a, b)` triple of longs.
const RANGE_ALL: jlong = 0;
const RANGE_BOUNDED: jlong = 1;
const RANGE_FROM: jlong = 2;
const RANGE_SUFFIX: jlong = 3;

fn byte_range(kind: jlong, a: jlong, b: jlong) -> NativeResult<ByteRange> {
    let offset = |v: jlong| {
        u64::try_from(v).map_err(|_| {
            NativeError::invalid_argument(format!(
                "byte offset must not be negative: {v}"
            ))
        })
    };
    match kind {
        RANGE_ALL => Ok(ByteRange::From(0)),
        RANGE_BOUNDED => {
            let (start, end) = (offset(a)?, offset(b)?);
            if end < start {
                return Err(NativeError::invalid_argument(format!(
                    "byte range end {end} is before start {start}"
                )));
            }
            Ok(ByteRange::Bounded(start..end))
        }
        RANGE_FROM => Ok(ByteRange::From(offset(a)?)),
        RANGE_SUFFIX => Ok(ByteRange::Last(offset(a)?)),
        other => {
            Err(NativeError::invalid_argument(format!("unknown range kind {other}")))
        }
    }
}

/// Map "key not found" to `None` and every other error through.
fn found<T>(result: Result<T, StoreError>) -> NativeResult<Option<T>> {
    match result {
        Ok(value) => Ok(Some(value)),
        Err(StoreError { kind: StoreErrorKind::NotFound(_), .. }) => Ok(None),
        Err(err) => Err(err.into()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGet<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    key: JString<'l>,
    range_kind: jlong,
    a: jlong,
    b: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let key = text(env, &key)?;
            let range = byte_range(range_kind, a, b)?;
            Ok(async move { Ok(Reply::Bytes(found(store.get(&key, &range).await)?)) })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGetMany<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    keys: JObjectArray<'l, JString<'static>>,
    ranges: JLongArray<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let count = keys.len(env)?;
            let mut triples = vec![0; count * 3];
            if ranges.len(env)? != triples.len() {
                return Err(NativeError::invalid_argument(
                    "expected one (kind, a, b) range triple per key",
                ));
            }
            ranges.get_region(env, 0, &mut triples)?;
            let mut requests = Vec::with_capacity(count);
            for (index, triple) in triples.chunks_exact(3).enumerate() {
                let key = keys.get_element(env, index)?;
                let key = text(env, &key)?;
                requests.push((key, byte_range(triple[0], triple[1], triple[2])?));
            }
            Ok(async move {
                let values = store
                    .get_partial_values(requests)
                    .await?
                    .into_iter()
                    .map(found)
                    .collect::<NativeResult<Vec<_>>>()?;
                Ok(Reply::BytesList(values))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeSet<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    key: JString<'l>,
    value: JByteArray<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let key = text(env, &key)?;
            let value = Bytes::from(env.convert_byte_array(&value)?);
            Ok(async move {
                store.set(&key, value).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeSetIfNotExists<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    key: JString<'l>,
    value: JByteArray<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let key = text(env, &key)?;
            let value = Bytes::from(env.convert_byte_array(&value)?);
            Ok(async move {
                store.set_if_not_exists(&key, value).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeExists<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    key: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let key = text(env, &key)?;
            Ok(async move { Ok(Reply::Bool(store.exists(&key).await?)) })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeSize<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    key: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let key = text(env, &key)?;
            Ok(async move {
                // icechunk reports a missing chunk as size 0, so existence is checked
                // separately to tell "missing" from "empty".
                if !store.exists(&key).await? {
                    return Ok(Reply::Long(-1));
                }
                let size = match found(store.getsize(&key).await)? {
                    Some(size) => i64::try_from(size).map_err(|_| {
                        NativeError::invalid_argument("object size exceeds a Java long")
                    })?,
                    None => -1,
                };
                Ok(Reply::Long(size))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeDelete<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    key: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let key = text(env, &key)?;
            Ok(async move {
                store.delete(&key).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeDeleteDir<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    prefix: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let prefix = text(env, &prefix)?;
            Ok(async move {
                store.delete_dir(&prefix).await?;
                Ok(Reply::Void)
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeIsEmpty<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    prefix: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let prefix = text(env, &prefix)?;
            Ok(async move { Ok(Reply::Bool(store.is_empty(&prefix).await?)) })
        })
    })
}

/// List modes; the values match the `Native.LIST_*` constants.
const LIST_ALL: jint = 0;
const LIST_PREFIX: jint = 1;
const LIST_DIR: jint = 2;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeList<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
    mode: jint,
    prefix: JString<'l>,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |env, _| {
            let store = handles::store(store)?;
            let prefix = text(env, &prefix)?;
            if !matches!(mode, LIST_ALL | LIST_PREFIX | LIST_DIR) {
                return Err(NativeError::invalid_argument(format!(
                    "unknown list mode {mode}"
                )));
            }
            Ok(async move {
                let keys: Vec<String> = match mode {
                    LIST_ALL => store.list().await?.try_collect().await?,
                    LIST_PREFIX => {
                        store.list_prefix(&prefix).await?.try_collect().await?
                    }
                    _ => store.list_dir(&prefix).await?.try_collect().await?,
                };
                Ok(Reply::Texts(keys))
            })
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeReadOnly<'l>(
    env: EnvUnowned<'l>,
    class: JClass<'l>,
    callback: JObject<'l>,
    store: jlong,
) -> jlong {
    call::entry(env, |env| {
        call::start(env, &class, &callback, |_, _| {
            let store = handles::store(store)?;
            Ok(async move { Ok(Reply::Bool(store.read_only().await)) })
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges() {
        assert_eq!(byte_range(RANGE_ALL, 0, 0).unwrap(), ByteRange::From(0));
        assert_eq!(byte_range(RANGE_BOUNDED, 2, 5).unwrap(), ByteRange::Bounded(2..5));
        assert_eq!(byte_range(RANGE_FROM, 7, 0).unwrap(), ByteRange::From(7));
        assert_eq!(byte_range(RANGE_SUFFIX, 4, 0).unwrap(), ByteRange::Last(4));
        assert!(byte_range(RANGE_BOUNDED, 5, 2).is_err());
        assert!(byte_range(RANGE_FROM, -1, 0).is_err());
        assert!(byte_range(9, 0, 0).is_err());
    }
}
