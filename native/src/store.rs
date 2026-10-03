//! Native methods for `Store`, the Zarr key/value view of a session.

use bytes::Bytes;
use futures::TryStreamExt as _;
use icechunk::format::ByteRange;
use icechunk::session::SessionErrorKind;
use icechunk::store::{StoreError, StoreErrorKind};
use jni::Env;
use jni::objects::{JByteArray, JByteBuffer, JLongArray, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong};

use crate::buffers;
use crate::call::{block_on, native, strings, text};
use crate::error::{NativeError, NativeResult};
use crate::handles;

/// Byte range kinds; the values match the `Native.RANGE_*` constants. Each range
/// travels as a `(kind, a, b)` triple of longs.
pub(crate) const RANGE_BOUNDED: jlong = 1;
pub(crate) const RANGE_FROM: jlong = 2;
pub(crate) const RANGE_SUFFIX: jlong = 3;

/// List modes; the values match the `Native.LIST_*` constants.
pub(crate) const LIST_ALL: jint = 0;
pub(crate) const LIST_PREFIX: jint = 1;
pub(crate) const LIST_DIR: jint = 2;

fn byte_range(kind: jlong, a: jlong, b: jlong) -> NativeResult<ByteRange> {
    let offset = |v: jlong| {
        u64::try_from(v).map_err(|_| {
            NativeError::invalid_argument(format!(
                "byte offset must not be negative: {v}"
            ))
        })
    };
    match kind {
        RANGE_BOUNDED => {
            let (start, end) = (offset(a)?, offset(b)?);
            if end < start {
                return Err(NativeError::invalid_argument(format!(
                    "byte range end {end} is before start {start}"
                )));
            }
            Ok(ByteRange::bounded(start, end))
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

fn get(store: jlong, key: &str, range: &ByteRange) -> NativeResult<Option<Bytes>> {
    let store = handles::store(store)?;
    found(block_on(store.get(key, range))?)
}

fn get_many(
    env: &mut Env<'_>,
    store: jlong,
    keys: &JObjectArray<'_, JString<'_>>,
    ranges: &JLongArray<'_>,
) -> NativeResult<Vec<Option<Bytes>>> {
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
        requests.push((text(env, &key)?, byte_range(triple[0], triple[1], triple[2])?));
        env.delete_local_ref(key);
    }
    let values = block_on(store.get_partial_values(requests))??;
    values.into_iter().map(found).collect()
}

// A new `byte[]` with the value, or null when the key does not exist.
native! { fn storeGet(
    env, store: jlong, key: JString<'l>, range_kind: jlong, a: jlong, b: jlong
) -> JByteArray<'l> {
    let key = text(env, &key)?;
    match get(store, &key, &byte_range(range_kind, a, b)?)? {
        Some(bytes) => Ok(env.byte_array_from_slice(&bytes)?),
        None => Ok(JByteArray::default()),
    }
}}

// Batch read: a `byte[][]` with null for missing keys, fetched concurrently by icechunk.
native! { fn storeGetPartialValues(
    env, store: jlong, keys: JObjectArray<'l, JString<'l>>, ranges: JLongArray<'l>
) -> JObjectArray<'l, JByteArray<'l>> {
    let values = get_many(env, store, &keys, &ranges)?;
    let array = JObjectArray::<JByteArray<'_>>::new(env, values.len(), JByteArray::default())?;
    // Consume the values so each Rust buffer is freed right after its copy, instead of
    // all of them staying alive until the whole batch is copied.
    for (index, value) in values.into_iter().enumerate() {
        if let Some(bytes) = value {
            let bytes = env.byte_array_from_slice(&bytes)?;
            array.set_element(env, index, &bytes)?;
            env.delete_local_ref(bytes);
        }
    }
    Ok(array)
}}

fn set(store: jlong, key: &str, value: Bytes, only_if_new: bool) -> NativeResult<()> {
    let store = handles::store(store)?;
    if only_if_new {
        block_on(store.set_if_not_exists(key, value))??;
    } else {
        block_on(store.set(key, value))??;
    }
    Ok(())
}

// Write `length` bytes of the heap array `value` starting at `offset`, for a heap
// `ByteBuffer`. The bytes are copied: the JVM may move the array.
native! { fn storeSet(
    env, store: jlong, key: JString<'l>, value: JByteArray<'l>, offset: jint, length: jint,
    only_if_new: jboolean
) -> () {
    let key = text(env, &key)?;
    let len =
        usize::try_from(length).map_err(|_| NativeError::invalid_argument("negative length"))?;
    let mut data = vec![0u8; len];
    // SAFETY: `i8` and `u8` have the same size and alignment, and the bytes are
    // initialised, so the vector can be viewed as `i8`.
    let view = unsafe { std::slice::from_raw_parts_mut(data.as_mut_ptr().cast::<i8>(), len) };
    value.get_region(env, offset, view)?;
    set(store, &key, Bytes::from(data), only_if_new)
}}

// Write `length` bytes of the direct buffer `value` starting at `position`. Large values
// are read in place; the caller must not modify that region afterwards.
native! { fn storeSetBuffer(
    env, store: jlong, key: JString<'l>, value: JByteBuffer<'l>, position: jint,
    length: jint, only_if_new: jboolean
) -> () {
    let key = text(env, &key)?;
    let (Ok(position), Ok(len)) = (usize::try_from(position), usize::try_from(length)) else {
        return Err(NativeError::invalid_argument("negative position or length"));
    };
    let bytes = buffers::borrow(env, &value, position, len)?;
    set(store, &key, bytes, only_if_new)
}}

native! { fn storeExists(env, store: jlong, key: JString<'l>) -> jboolean {
    let store = handles::store(store)?;
    let key = text(env, &key)?;
    // icechunk reports a Zarr v2 metadata key, which it never stores, as not found.
    Ok(found(block_on(store.exists(&key))?)?.unwrap_or(false))
}}

native! { fn storeGetSize(env, store: jlong, key: JString<'l>) -> jlong {
    let store = handles::store(store)?;
    let key = text(env, &key)?;
    let size = block_on(async {
        let size = found(store.getsize(&key).await)?;
        // icechunk reports a missing chunk as size 0 rather than as missing, so a 0 needs
        // a second look to tell "missing" from "empty".
        if size == Some(0) && !store.exists(&key).await? {
            return Ok(None);
        }
        Ok::<_, NativeError>(size)
    })??;
    match size {
        Some(size) => i64::try_from(size)
            .map_err(|_| NativeError::invalid_argument("object size exceeds a Java long")),
        None => Ok(-1),
    }
}}

native! { fn storeDelete(env, store: jlong, key: JString<'l>) -> () {
    let store = handles::store(store)?;
    let key = text(env, &key)?;
    Ok(block_on(store.delete(&key))??)
}}

native! { fn storeDeleteDir(env, store: jlong, prefix: JString<'l>) -> () {
    let store = handles::store(store)?;
    let prefix = text(env, &prefix)?;
    Ok(block_on(store.delete_dir(&prefix))??)
}}

native! { fn storeGetSizePrefix(env, store: jlong, prefix: JString<'l>) -> jlong {
    let store = handles::store(store)?;
    let prefix = text(env, &prefix)?;
    // icechunk's `getsize_prefix` takes a second read lock on the session while holding
    // the first, which deadlocks once a writer queues between them. Listing and then
    // sizing each key takes every lock on its own.
    let size = block_on(async {
        let keys: Vec<String> = store.list_prefix(&prefix).await?.try_collect().await?;
        let mut total = 0u64;
        for key in keys {
            match store.getsize(&key).await {
                Ok(size) => total += size,
                // Deleted by another thread since the listing.
                Err(StoreError {
                    kind:
                        StoreErrorKind::NotFound(_)
                        | StoreErrorKind::SessionError(SessionErrorKind::NodeNotFound {
                            ..
                        }),
                    ..
                }) => {}
                Err(err) => return Err(err),
            }
        }
        Ok::<_, StoreError>(total)
    })??;
    i64::try_from(size).map_err(|_| NativeError::invalid_argument("prefix size exceeds a Java long"))
}}

native! { fn storeClear(_, store: jlong) -> () {
    let store = handles::store(store)?;
    Ok(block_on(store.clear())??)
}}

native! { fn storeIsEmpty(env, store: jlong, prefix: JString<'l>) -> jboolean {
    let store = handles::store(store)?;
    let prefix = text(env, &prefix)?;
    Ok(block_on(store.is_empty(&prefix))??)
}}

native! { fn storeList(
    env, store: jlong, mode: jint, prefix: JString<'l>
) -> JObjectArray<'l, JString<'l>> {
    let store = handles::store(store)?;
    let prefix = text(env, &prefix)?;
    let keys: Vec<String> = block_on(async {
        match mode {
            LIST_ALL => store.list().await?.try_collect().await,
            LIST_PREFIX => store.list_prefix(&prefix).await?.try_collect().await,
            LIST_DIR => store.list_dir(&prefix).await?.try_collect().await,
            other => Err(StoreError::capture(StoreErrorKind::Other(format!(
                "unknown list mode {other}"
            )))),
        }
    })??;
    strings(env, keys.iter())
}}

native! { fn storeReadOnly(_, store: jlong) -> jboolean {
    let store = handles::store(store)?;
    block_on(store.read_only())
}}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges() {
        assert_eq!(byte_range(RANGE_BOUNDED, 2, 5).unwrap(), ByteRange::bounded(2, 5));
        assert_eq!(byte_range(RANGE_FROM, 7, 0).unwrap(), ByteRange::From(7));
        assert_eq!(byte_range(RANGE_SUFFIX, 4, 0).unwrap(), ByteRange::Last(4));
        assert!(byte_range(RANGE_BOUNDED, 5, 2).is_err());
        assert!(byte_range(RANGE_FROM, -1, 0).is_err());
        assert!(byte_range(9, 0, 0).is_err());
    }
}
