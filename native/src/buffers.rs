//! Moving bytes between icechunk and Java without copying.
//!
//! Reads: a value is handed to Java as a direct `ByteBuffer` over icechunk's own
//! `Bytes`. The `Bytes` is kept alive by a boxed owner whose address Java holds, and is
//! dropped when Java's `Cleaner` calls `bufferRelease` after the buffer becomes
//! unreachable. Slices and duplicates of a direct buffer keep the original reachable, so
//! the memory cannot be released while any view of it exists.
//!
//! Writes: a large direct `ByteBuffer` from Java is wrapped as a `Bytes` that holds a
//! global reference to the buffer, so icechunk reads Java's memory in place. Small values
//! are copied, since icechunk keeps them in the session's change set until commit.

use std::sync::atomic::{AtomicI64, Ordering};

use bytes::Bytes;
use jni::Env;
use jni::objects::{Global, JByteBuffer, JObject};
use jni::sys::jlong;

use crate::error::{NativeError, NativeResult};

/// Bytes currently lent to Java as direct buffers. Java reads it to decide when to
/// prompt a garbage collection, since the collector cannot see native memory.
static OUTSTANDING: AtomicI64 = AtomicI64::new(0);

/// Values up to this size are copied on write rather than borrowed from Java.
const BORROW_THRESHOLD: usize = 64 * 1024;

pub(crate) fn outstanding() -> i64 {
    OUTSTANDING.load(Ordering::Relaxed)
}

/// Wrap `bytes` as a direct `ByteBuffer`. Returns the buffer and the owner address Java
/// must pass to [`release`] exactly once, or 0 when there is nothing to release.
pub(crate) fn lend<'local>(
    env: &mut Env<'local>,
    bytes: Bytes,
) -> NativeResult<(JByteBuffer<'local>, jlong)> {
    let len = bytes.len();
    if len == 0 {
        // A zero-length direct buffer needs no native memory behind it.
        // SAFETY: a capacity of 0 means the JVM never dereferences the address.
        let buffer = unsafe {
            env.new_direct_byte_buffer(std::ptr::NonNull::dangling().as_ptr(), 0)?
        };
        return Ok((buffer, 0));
    }
    let owner = Box::new(bytes);
    let data = owner.as_ptr().cast_mut();
    let owner = Box::into_raw(owner);
    // SAFETY: `data` points to `len` bytes owned by `owner`, which stays alive until Java
    // calls `release`. The buffer is made read-only on the Java side before it is shared,
    // so nothing writes through the `*mut` the JNI signature requires.
    let buffer = match unsafe { env.new_direct_byte_buffer(data, len) } {
        Ok(buffer) => buffer,
        Err(err) => {
            // SAFETY: `owner` came from `Box::into_raw` above and was never handed out.
            drop(unsafe { Box::from_raw(owner) });
            return Err(err.into());
        }
    };
    OUTSTANDING.fetch_add(len as i64, Ordering::Relaxed);
    Ok((buffer, owner as jlong))
}

/// Drop a value lent by [`lend`].
///
/// # Safety
///
/// `owner` must be an address returned by [`lend`] that has not been released yet.
pub(crate) unsafe fn release(owner: jlong) {
    if owner == 0 {
        return;
    }
    // SAFETY: guaranteed by the caller.
    let bytes = unsafe { Box::from_raw(owner as *mut Bytes) };
    OUTSTANDING.fetch_sub(bytes.len() as i64, Ordering::Relaxed);
}

/// Keeps a Java direct buffer alive while icechunk reads it.
struct JavaBuffer {
    _buffer: Global<JByteBuffer<'static>>,
    data: *const u8,
    len: usize,
}

// SAFETY: the memory is a direct buffer's, which the JVM never moves or frees while the
// global reference keeps the buffer reachable. icechunk only reads it.
unsafe impl Send for JavaBuffer {}
// SAFETY: as above; shared access is read-only.
unsafe impl Sync for JavaBuffer {}

impl AsRef<[u8]> for JavaBuffer {
    fn as_ref(&self) -> &[u8] {
        // SAFETY: `data` and `len` describe the live region of the direct buffer.
        unsafe { std::slice::from_raw_parts(self.data, self.len) }
    }
}

/// The address of `len` bytes at `position` in a direct `ByteBuffer`, bounds checked.
pub(crate) fn direct_region(
    env: &mut Env<'_>,
    buffer: &JObject<'_>,
    position: usize,
    len: usize,
) -> NativeResult<*mut u8> {
    // SAFETY: `buffer` is a `java.nio.ByteBuffer`, checked to be direct on the Java side.
    let buffer = unsafe { JByteBuffer::from_raw(env, buffer.as_raw()) };
    let base = env.get_direct_buffer_address(&buffer)?;
    let capacity = env.get_direct_buffer_capacity(&buffer)?;
    if base.is_null() || position.checked_add(len).is_none_or(|end| end > capacity) {
        return Err(NativeError::invalid_argument("buffer region out of bounds"));
    }
    // SAFETY: bounds checked against the buffer's capacity above.
    Ok(unsafe { base.add(position) })
}

/// Read `len` bytes at `position` of a direct `ByteBuffer`, borrowing them when large.
pub(crate) fn borrow(
    env: &mut Env<'_>,
    buffer: &JObject<'_>,
    position: usize,
    len: usize,
) -> NativeResult<Bytes> {
    let data = direct_region(env, buffer, position, len)?;
    if len <= BORROW_THRESHOLD {
        // SAFETY: as above, and the slice is copied before this call returns.
        return Ok(Bytes::copy_from_slice(unsafe {
            std::slice::from_raw_parts(data, len)
        }));
    }
    // SAFETY: as in `direct_region`, `buffer` is a direct `java.nio.ByteBuffer`.
    let buffer = unsafe { JByteBuffer::from_raw(env, buffer.as_raw()) };
    let global = env.new_global_ref(&buffer)?;
    Ok(Bytes::from_owner(JavaBuffer { _buffer: global, data, len }))
}
