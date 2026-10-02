//! Reading Java's direct `ByteBuffer`s on write without copying them.
//!
//! A large direct buffer is wrapped as a `Bytes` that holds a global reference to the
//! buffer, so icechunk reads Java's memory in place. Heap arrays cannot be borrowed this
//! way, because the JVM may move them; they are copied (see `storeSet`).

use bytes::Bytes;
use jni::Env;
use jni::objects::{Global, JByteBuffer};

use crate::error::{NativeError, NativeResult};

/// Values up to this size are copied rather than borrowed from Java. Borrowing costs a JNI
/// global reference, which is not worth it for small values, and icechunk keeps values
/// below a repository's `inline_chunk_threshold_bytes` (512 by default) in the change set
/// until commit. Borrowing stays safe above that threshold, since the global reference
/// keeps the buffer alive, but the caller must leave it unmodified until then.
const BORROW_THRESHOLD: usize = 64 * 1024;

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

/// Read `len` bytes at `position` of a direct `ByteBuffer`, borrowing them when large.
pub(crate) fn borrow(
    env: &mut Env<'_>,
    buffer: &JByteBuffer<'_>,
    position: usize,
    len: usize,
) -> NativeResult<Bytes> {
    let base = env.get_direct_buffer_address(buffer)?;
    let capacity = env.get_direct_buffer_capacity(buffer)?;
    if base.is_null() || position.checked_add(len).is_none_or(|end| end > capacity) {
        return Err(NativeError::invalid_argument("buffer region out of bounds"));
    }
    // SAFETY: bounds checked against the buffer's capacity above.
    let data = unsafe { base.add(position) };
    if len <= BORROW_THRESHOLD {
        // SAFETY: as above, and the slice is copied before this call returns.
        return Ok(Bytes::copy_from_slice(unsafe {
            std::slice::from_raw_parts(data, len)
        }));
    }
    let global = env.new_global_ref(buffer)?;
    Ok(Bytes::from_owner(JavaBuffer { _buffer: global, data, len }))
}
