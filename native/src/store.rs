#![allow(
    unreachable_pub,
    reason = "JNI exports are found by symbol name, not Rust paths"
)]

use bytes::Bytes;
use futures::TryStreamExt as _;
use icechunk::format::ByteRange;
use icechunk::store::{StoreError, StoreErrorKind};
use jni::objects::{JByteArray, JClass, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::{Env, EnvUnowned, jni_str};

use crate::buffers;
use crate::call::{self, block_on, jsize, strings, text};
use crate::error::{NativeError, NativeResult};
use crate::handles;

/// Byte range kinds; the values match the `Native.RANGE_*` constants. Each range
/// travels as a `(kind, a, b)` triple of longs.
const RANGE_ALL: jlong = 0;
const RANGE_BOUNDED: jlong = 1;
const RANGE_FROM: jlong = 2;
const RANGE_SUFFIX: jlong = 3;

/// List modes; the values match the `Native.LIST_*` constants.
const LIST_ALL: jint = 0;
const LIST_PREFIX: jint = 1;
const LIST_DIR: jint = 2;

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

fn get(store: jlong, key: &str, range: &ByteRange) -> NativeResult<Option<Bytes>> {
    let store = handles::store(store)?;
    found(block_on(store.get(key, range))?)
}

fn get_many(
    env: &mut Env<'_>,
    store: jlong,
    keys: &JObjectArray<'_>,
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
        // SAFETY: the Java signature declares `keys` as a `String[]`.
        let key = unsafe { JString::from_raw(env, key.into_raw()) };
        requests.push((text(env, &key)?, byte_range(triple[0], triple[1], triple[2])?));
        env.delete_local_ref(key);
    }
    let values = block_on(store.get_partial_values(requests))??;
    values.into_iter().map(found).collect()
}

/// Copying read: a new `byte[]`, or null when the key does not exist.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGet<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
    range_kind: jlong,
    a: jlong,
    b: jlong,
) -> JByteArray<'l> {
    call::run(env, |env| {
        let key = text(env, &key)?;
        match get(store, &key, &byte_range(range_kind, a, b)?)? {
            Some(bytes) => Ok(env.byte_array_from_slice(&bytes)?),
            None => Ok(JByteArray::default()),
        }
    })
}

/// Zero-copy read: a direct `ByteBuffer` over icechunk's memory, or null when the key
/// does not exist. `out[0]` receives the owner to pass to `bufferRelease`, and `out[1]`
/// the total bytes currently lent to Java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGetBuffer<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
    range_kind: jlong,
    a: jlong,
    b: jlong,
    out: JLongArray<'l>,
) -> JObject<'l> {
    call::run(env, |env| {
        let key = text(env, &key)?;
        let Some(bytes) = get(store, &key, &byte_range(range_kind, a, b)?)? else {
            return Ok(JObject::null());
        };
        let (buffer, owner) = buffers::lend(env, bytes)?;
        out.set_region(env, 0, &[owner, buffers::outstanding()])?;
        Ok(JObject::from(buffer))
    })
}

/// Read into a buffer Java owns: the direct buffer `direct`, or else `array`, starting at
/// `offset` with room for `capacity` bytes. Returns the number of bytes written, -1 when
/// the key does not exist, or `-2 - size` when the value does not fit, in which case
/// nothing is written.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGetInto<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
    range_kind: jlong,
    a: jlong,
    b: jlong,
    direct: JObject<'l>,
    array: JByteArray<'l>,
    offset: jint,
    capacity: jint,
) -> jlong {
    call::run(env, |env| {
        let key = text(env, &key)?;
        let Some(bytes) = get(store, &key, &byte_range(range_kind, a, b)?)? else {
            return Ok(-1);
        };
        let (Ok(offset), Ok(capacity)) =
            (usize::try_from(offset), usize::try_from(capacity))
        else {
            return Err(NativeError::invalid_argument("negative offset or capacity"));
        };
        let len = bytes.len();
        if len > capacity {
            return Ok(-2 - len as jlong);
        }
        if direct.is_null() {
            // SAFETY: `i8` and `u8` have the same size and alignment.
            let view =
                unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast::<i8>(), len) };
            array.set_region(env, offset as i32, view)?;
        } else {
            let target = buffers::direct_region(env, &direct, offset, len)?;
            // SAFETY: `direct_region` checked that `len` bytes at `target` lie inside the
            // buffer, and the source is a separate Rust allocation.
            unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), target, len) };
        }
        Ok(len as jlong)
    })
}

/// Copying batch read: a `byte[][]` with null for missing keys.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGetMany<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    keys: JObjectArray<'l>,
    ranges: JLongArray<'l>,
) -> JObjectArray<'l> {
    call::run(env, |env| {
        let values = get_many(env, store, &keys, &ranges)?;
        let array =
            env.new_object_array(jsize(values.len())?, jni_str!("[B"), JObject::null())?;
        for (index, value) in values.iter().enumerate() {
            if let Some(bytes) = value {
                let bytes = env.byte_array_from_slice(bytes)?;
                array.set_element(env, index, &bytes)?;
                env.delete_local_ref(bytes);
            }
        }
        Ok(array)
    })
}

/// Zero-copy batch read: a `ByteBuffer[]` with null for missing keys. `out[i]` receives
/// the owner of element `i`, and `out[n]` the total bytes currently lent to Java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeGetManyBuffers<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    keys: JObjectArray<'l>,
    ranges: JLongArray<'l>,
    out: JLongArray<'l>,
) -> JObjectArray<'l> {
    call::run(env, |env| {
        let values = get_many(env, store, &keys, &ranges)?;
        let array = env.new_object_array(
            jsize(values.len())?,
            jni_str!("java/nio/ByteBuffer"),
            JObject::null(),
        )?;
        let mut owners = vec![0; values.len() + 1];
        for (index, value) in values.into_iter().enumerate() {
            if let Some(bytes) = value {
                let (buffer, owner) = buffers::lend(env, bytes)?;
                owners[index] = owner;
                array.set_element(env, index, &buffer)?;
                env.delete_local_ref(buffer);
            }
        }
        if let Some(last) = owners.last_mut() {
            *last = buffers::outstanding();
        }
        out.set_region(env, 0, &owners)?;
        Ok(array)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_bufferRelease<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    owner: jlong,
) {
    // SAFETY: `owner` comes from `storeGetBuffer` or `storeGetManyBuffers`, and the Java
    // `Cleaner` registered for it runs at most once.
    unsafe { buffers::release(owner) };
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_bufferOutstanding<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jlong {
    buffers::outstanding()
}

fn set(store: jlong, key: &str, value: Bytes, only_if_new: bool) -> NativeResult<()> {
    let store = handles::store(store)?;
    if only_if_new {
        block_on(store.set_if_not_exists(key, value))??;
    } else {
        block_on(store.set(key, value))??;
    }
    Ok(())
}

/// Write `length` bytes of `value` starting at `offset`. The bytes are copied.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeSet<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
    value: JByteArray<'l>,
    offset: jint,
    length: jint,
    only_if_new: jboolean,
) {
    call::run(env, |env| {
        let key = text(env, &key)?;
        let len = usize::try_from(length)
            .map_err(|_| NativeError::invalid_argument("negative length"))?;
        let mut data = vec![0u8; len];
        // SAFETY: `i8` and `u8` have the same size and alignment, so the slice can be
        // viewed as either.
        let view = unsafe {
            std::slice::from_raw_parts_mut(data.as_mut_ptr().cast::<i8>(), len)
        };
        value.get_region(env, offset, view)?;
        set(store, &key, Bytes::from(data), only_if_new)
    });
}

/// Write `length` bytes of the direct buffer `value` starting at `position`. Large
/// values are read in place; the caller must not modify that region afterwards.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeSetBuffer<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
    value: JObject<'l>,
    position: jint,
    length: jint,
    only_if_new: jboolean,
) {
    call::run(env, |env| {
        let key = text(env, &key)?;
        let (Ok(position), Ok(len)) =
            (usize::try_from(position), usize::try_from(length))
        else {
            return Err(NativeError::invalid_argument("negative position or length"));
        };
        let bytes = buffers::borrow(env, &value, position, len)?;
        set(store, &key, bytes, only_if_new)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeExists<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
) -> jboolean {
    call::run(env, |env| {
        let store = handles::store(store)?;
        let key = text(env, &key)?;
        Ok(block_on(store.exists(&key))??)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeSize<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
) -> jlong {
    call::run(env, |env| {
        let store = handles::store(store)?;
        let key = text(env, &key)?;
        let size = block_on(async {
            // icechunk reports a missing chunk as size 0, so existence is checked
            // separately to tell "missing" from "empty".
            if !store.exists(&key).await? {
                return Ok(None);
            }
            found(store.getsize(&key).await)
        })??;
        match size {
            Some(size) => i64::try_from(size).map_err(|_| {
                NativeError::invalid_argument("object size exceeds a Java long")
            }),
            None => Ok(-1),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeDelete<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    key: JString<'l>,
) {
    call::run(env, |env| {
        let store = handles::store(store)?;
        let key = text(env, &key)?;
        Ok(block_on(store.delete(&key))??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeDeleteDir<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    prefix: JString<'l>,
) {
    call::run(env, |env| {
        let store = handles::store(store)?;
        let prefix = text(env, &prefix)?;
        Ok(block_on(store.delete_dir(&prefix))??)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeIsEmpty<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    prefix: JString<'l>,
) -> jboolean {
    call::run(env, |env| {
        let store = handles::store(store)?;
        let prefix = text(env, &prefix)?;
        Ok(block_on(store.is_empty(&prefix))??)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeList<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
    mode: jint,
    prefix: JString<'l>,
) -> JObjectArray<'l> {
    call::run(env, |env| {
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
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_earthmover_icechunk_Native_storeReadOnly<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    store: jlong,
) -> jboolean {
    call::run(env, |_| {
        let store = handles::store(store)?;
        block_on(store.read_only())
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
