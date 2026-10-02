//! The table that maps the `long` handles Java holds to live Rust objects.
//!
//! Java never holds a pointer. A handle is a slot index plus a generation number. Closing
//! a handle empties its slot, and the slot is reused only with a new generation, so:
//!
//! - an operation already running keeps using the object and finishes normally;
//! - a later lookup with the old handle fails with `ErrorKind::Closed` instead of
//!   touching freed memory;
//! - a reused slot never answers to a handle from its previous occupant.
//!
//! Lookups take no lock and write no shared memory. Every Java thread looks up handles on
//! every call, and with a lock or reference count those threads would contend on one
//! cache line: with 8 threads, a `std::sync::RwLock` read costs microseconds. Instead a
//! lookup pins the current thread with `crossbeam_epoch`, which is thread-local, and
//! borrows the object for as long as the returned [`Ref`] lives. Closing a handle unlinks
//! the object and defers freeing it until every thread that might still hold a `Ref` to
//! it has unpinned. A `Ref` held across a long call therefore delays freeing objects
//! closed during that call; extensions doing long network I/O can copy the `Arc` out of
//! the `Ref` and drop it first.

use std::any::Any;
use std::ops::Deref;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, OnceLock, PoisonError};

use crossbeam_epoch::{self as epoch, Atomic, Guard, Owned};
use icechunk::session::Session;
use icechunk::{Repository, Storage, Store};

use crate::error::{ErrorKind, NativeError, NativeResult};

pub(crate) type StorageRef = Arc<dyn Storage + Send + Sync>;
pub(crate) type RepositoryRef = Arc<Repository>;
pub(crate) type SessionRef = Arc<tokio::sync::RwLock<Session>>;
pub(crate) type StoreRef = Arc<Store>;

pub(crate) enum Object {
    Storage(StorageRef),
    Repository(RepositoryRef),
    Session(SessionRef),
    Store(StoreRef),
    /// An object owned by an extension library; see `crate::ext`.
    Extension(Arc<dyn Any + Send + Sync>),
}

impl Object {
    fn type_name(&self) -> &'static str {
        match self {
            Object::Storage(_) => "Storage",
            Object::Repository(_) => "Repository",
            Object::Session(_) => "Session",
            Object::Store(_) => "Store",
            Object::Extension(_) => "extension",
        }
    }
}

struct Entry {
    generation: u32,
    object: Object,
}

/// Slots live in fixed segments that are never moved or freed, so a lookup can index
/// them without a lock while another thread adds segments.
const SEGMENT_BITS: u32 = 12;
const SEGMENT_LEN: usize = 1 << SEGMENT_BITS;
const SEGMENTS: usize = 1024;

type Segment = Box<[Atomic<Entry>]>;

static SEGMENT_TABLE: [OnceLock<Segment>; SEGMENTS] =
    [const { OnceLock::new() }; SEGMENTS];

/// Bookkeeping for inserts and removals, which are rare compared with lookups.
struct Allocator {
    /// The next generation for each slot ever used.
    generations: Vec<u32>,
    free: Vec<u32>,
}

static ALLOCATOR: Mutex<Allocator> =
    Mutex::new(Allocator { generations: Vec::new(), free: Vec::new() });

fn encode(index: u32, generation: u32) -> i64 {
    ((u64::from(generation) << 32) | u64::from(index)) as i64
}

fn decode(handle: i64) -> (u32, u32) {
    let raw = handle as u64;
    ((raw & u64::from(u32::MAX)) as u32, (raw >> 32) as u32)
}

fn slot(index: u32) -> Option<&'static Atomic<Entry>> {
    let segment = SEGMENT_TABLE.get((index >> SEGMENT_BITS) as usize)?.get()?;
    segment.get(index as usize & (SEGMENT_LEN - 1))
}

pub(crate) fn insert(object: Object) -> NativeResult<i64> {
    let mut allocator = ALLOCATOR.lock().unwrap_or_else(PoisonError::into_inner);
    let index = match allocator.free.pop() {
        Some(index) => index,
        None => {
            let index = u32::try_from(allocator.generations.len())
                .ok()
                .filter(|i| (*i as usize) < SEGMENTS * SEGMENT_LEN)
                .ok_or_else(|| {
                    NativeError::new(ErrorKind::Icechunk, "too many open handles")
                })?;
            SEGMENT_TABLE[(index >> SEGMENT_BITS) as usize]
                .get_or_init(|| (0..SEGMENT_LEN).map(|_| Atomic::null()).collect());
            // Generations start at 1 so that 0 is never a valid handle.
            allocator.generations.push(1);
            index
        }
    };
    let generation = allocator.generations[index as usize];
    let Some(slot) = slot(index) else {
        return Err(NativeError::new(
            ErrorKind::Icechunk,
            "handle table is inconsistent",
        ));
    };
    slot.store(Owned::new(Entry { generation, object }), Ordering::Release);
    Ok(encode(index, generation))
}

/// A borrowed object, valid while this value lives.
pub struct Ref<T: 'static> {
    _guard: Guard,
    value: *const T,
}

impl<T: std::fmt::Debug> std::fmt::Debug for Ref<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(&**self, f)
    }
}

impl<T> Deref for Ref<T> {
    type Target = T;

    fn deref(&self) -> &T {
        // SAFETY: `value` points into an `Entry` that was reachable when the guard was
        // pinned. Removal defers freeing entries until all such guards are dropped.
        unsafe { &*self.value }
    }
}

fn get<T>(
    handle: i64,
    select: impl FnOnce(&Object) -> Option<&T>,
    expected: &str,
) -> NativeResult<Ref<T>> {
    let (index, generation) = decode(handle);
    let closed = || NativeError::new(ErrorKind::Closed, "handle is closed");
    let slot = slot(index).ok_or_else(closed)?;
    let guard = epoch::pin();
    let shared = slot.load(Ordering::Acquire, &guard);
    // SAFETY: entries are only freed through `defer_destroy`, after `guard` is dropped.
    let entry = unsafe { shared.as_ref() }.ok_or_else(closed)?;
    if entry.generation != generation {
        return Err(closed());
    }
    let value = select(&entry.object).ok_or_else(|| {
        NativeError::invalid_argument(format!(
            "expected a {expected} handle, got a {} handle",
            entry.object.type_name()
        ))
    })?;
    let value: *const T = value;
    Ok(Ref { _guard: guard, value })
}

/// Close a handle. Closing an already closed handle is a no-op.
///
/// The entry is unlinked under the allocator lock, but handed to the epoch collector
/// only after the lock is released: collecting can run the destructors of earlier closed
/// objects, and an extension object's destructor may itself close or open handles.
pub(crate) fn remove(handle: i64) {
    let (index, generation) = decode(handle);
    let Some(slot) = slot(index) else { return };
    let guard = epoch::pin();
    let unlinked = {
        let mut allocator = ALLOCATOR.lock().unwrap_or_else(PoisonError::into_inner);
        let current = slot.load(Ordering::Acquire, &guard);
        // SAFETY: the entry stays allocated while `guard` is pinned.
        match unsafe { current.as_ref() } {
            Some(entry) if entry.generation == generation => {}
            _ => return,
        }
        slot.store(epoch::Shared::null(), Ordering::Release);
        if let Some(next) = allocator.generations.get_mut(index as usize) {
            *next = next.wrapping_add(1).max(1);
        }
        allocator.free.push(index);
        current
    };
    // SAFETY: the entry is no longer reachable from the table, so only threads pinned
    // before it was unlinked can hold a reference, and `defer_destroy` waits for them.
    unsafe { guard.defer_destroy(unlinked) };
    // Hand the entry to the global queue now. Otherwise it waits in this thread's local
    // batch, which is collected only after many more deferrals from the same thread.
    guard.flush();
}

pub(crate) fn storage(handle: i64) -> NativeResult<Ref<StorageRef>> {
    get(handle, |o| if let Object::Storage(s) = o { Some(s) } else { None }, "Storage")
}

pub(crate) fn repository(handle: i64) -> NativeResult<Ref<RepositoryRef>> {
    get(
        handle,
        |o| if let Object::Repository(r) = o { Some(r) } else { None },
        "Repository",
    )
}

pub(crate) fn session(handle: i64) -> NativeResult<Ref<SessionRef>> {
    get(handle, |o| if let Object::Session(s) = o { Some(s) } else { None }, "Session")
}

pub(crate) fn store(handle: i64) -> NativeResult<Ref<StoreRef>> {
    get(handle, |o| if let Object::Store(s) = o { Some(s) } else { None }, "Store")
}

pub(crate) fn extension<T: 'static>(handle: i64) -> NativeResult<Ref<T>> {
    get(
        handle,
        |o| if let Object::Extension(e) = o { e.downcast_ref::<T>() } else { None },
        "matching extension",
    )
}
#[cfg(test)]
mod tests {
    use super::*;

    fn extension_object(value: u64) -> Object {
        Object::Extension(Arc::new(value))
    }

    #[test]
    fn encode_decode_roundtrip() {
        for (index, generation) in [(0, 1), (7, 3), (u32::MAX, u32::MAX)] {
            assert_eq!(decode(encode(index, generation)), (index, generation));
        }
    }

    #[test]
    fn zero_is_never_live() {
        assert_eq!(store(0).map(|_| ()).unwrap_err().kind, ErrorKind::Closed);
    }

    #[test]
    fn closed_and_reused_handles_are_rejected() {
        let first = insert(extension_object(1)).unwrap();
        assert_eq!(*extension::<u64>(first).unwrap(), 1);
        remove(first);
        remove(first);
        assert_eq!(
            extension::<u64>(first).map(|_| ()).unwrap_err().kind,
            ErrorKind::Closed
        );
        let second = insert(extension_object(2)).unwrap();
        assert_eq!(
            extension::<u64>(first).map(|_| ()).unwrap_err().kind,
            ErrorKind::Closed
        );
        assert_eq!(*extension::<u64>(second).unwrap(), 2);
        remove(second);
    }

    #[test]
    fn closed_objects_are_released() {
        use std::sync::atomic::{AtomicBool, Ordering};

        struct Flag(Arc<AtomicBool>);
        impl Drop for Flag {
            fn drop(&mut self) {
                self.0.store(true, Ordering::SeqCst);
            }
        }

        let dropped = Arc::new(AtomicBool::new(false));
        let handle =
            insert(Object::Extension(Arc::new(Flag(Arc::clone(&dropped))))).unwrap();
        remove(handle);
        // No thread holds a `Ref` to it, so collections must run the destructor once
        // tests on other threads unpin; a fixed number of tries can lose to them.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        while std::time::Instant::now() < deadline {
            if dropped.load(Ordering::SeqCst) {
                return;
            }
            epoch::pin().flush();
            std::thread::yield_now();
        }
        panic!("closed object was not released");
    }

    #[test]
    fn wrong_type_is_an_invalid_argument() {
        let handle = insert(extension_object(1)).unwrap();
        assert_eq!(
            store(handle).map(|_| ()).unwrap_err().kind,
            ErrorKind::InvalidArgument
        );
        remove(handle);
    }
}
