//! The table that maps the `long` handles Java holds to live Rust objects.
//!
//! Java never holds a pointer. A handle is a slot index plus a generation number, and
//! every lookup returns a clone of the slot's `Arc`. Closing a handle empties the slot
//! and bumps its generation, so:
//!
//! - an operation already running keeps its own `Arc` and finishes normally;
//! - a later lookup with the old handle fails with `ErrorKind::Closed` instead of
//!   touching freed memory;
//! - a reused slot never answers to a handle from its previous occupant.

use std::any::Any;
use std::sync::{Arc, PoisonError, RwLock};

use icechunk::session::Session;
use icechunk::{Repository, Storage, Store};

use crate::error::{ErrorKind, NativeError, NativeResult};

pub(crate) type StorageRef = Arc<dyn Storage + Send + Sync>;
pub(crate) type RepositoryRef = Arc<Repository>;
pub(crate) type SessionRef = Arc<tokio::sync::RwLock<Session>>;
pub(crate) type StoreRef = Arc<Store>;

#[derive(Clone)]
pub(crate) enum Object {
    Storage(StorageRef),
    Repository(RepositoryRef),
    Session(SessionRef),
    Store(StoreRef),
    /// An object owned by an extension library; see `crate::ext`.
    Extension(Arc<dyn Any + Send + Sync>),
}

impl std::fmt::Debug for Object {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.type_name())
    }
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

#[derive(Debug)]
struct Slot {
    generation: u32,
    entry: Option<Object>,
}

#[derive(Debug, Default)]
struct Table {
    slots: Vec<Slot>,
    free: Vec<u32>,
}

static TABLE: RwLock<Table> = RwLock::new(Table { slots: Vec::new(), free: Vec::new() });

fn encode(index: u32, generation: u32) -> i64 {
    ((u64::from(generation) << 32) | u64::from(index)) as i64
}

fn decode(handle: i64) -> (usize, u32) {
    let raw = handle as u64;
    ((raw & u64::from(u32::MAX)) as usize, (raw >> 32) as u32)
}

pub(crate) fn insert(object: Object) -> NativeResult<i64> {
    let mut table = TABLE.write().unwrap_or_else(PoisonError::into_inner);
    if let Some(index) = table.free.pop() {
        let slot = &mut table.slots[index as usize];
        slot.entry = Some(object);
        return Ok(encode(index, slot.generation));
    }
    let index = u32::try_from(table.slots.len())
        .map_err(|_| NativeError::new(ErrorKind::Icechunk, "too many open handles"))?;
    // Generations start at 1 so that 0 is never a valid handle.
    table.slots.push(Slot { generation: 1, entry: Some(object) });
    Ok(encode(index, 1))
}

fn get(handle: i64) -> NativeResult<Object> {
    let (index, generation) = decode(handle);
    let table = TABLE.read().unwrap_or_else(PoisonError::into_inner);
    match table.slots.get(index) {
        Some(Slot { generation: live, entry: Some(object) }) if *live == generation => {
            Ok(object.clone())
        }
        _ => Err(NativeError::new(ErrorKind::Closed, "handle is closed")),
    }
}

/// Close a handle. Closing an already closed handle is a no-op.
///
/// The object is dropped after the table lock is released, because dropping the last
/// reference to it can do arbitrary work.
pub(crate) fn remove(handle: i64) {
    let (index, generation) = decode(handle);
    let removed = {
        let mut table = TABLE.write().unwrap_or_else(PoisonError::into_inner);
        let Some(slot) = table.slots.get_mut(index) else { return };
        if slot.generation != generation || slot.entry.is_none() {
            return;
        }
        slot.generation = slot.generation.wrapping_add(1).max(1);
        let removed = slot.entry.take();
        table.free.push(index as u32);
        removed
    };
    drop(removed);
}

fn wrong_type(expected: &str, found: &Object) -> NativeError {
    NativeError::invalid_argument(format!(
        "expected a {expected} handle, got a {} handle",
        found.type_name()
    ))
}

pub(crate) fn storage(handle: i64) -> NativeResult<StorageRef> {
    match get(handle)? {
        Object::Storage(storage) => Ok(storage),
        other => Err(wrong_type("Storage", &other)),
    }
}

pub(crate) fn repository(handle: i64) -> NativeResult<RepositoryRef> {
    match get(handle)? {
        Object::Repository(repository) => Ok(repository),
        other => Err(wrong_type("Repository", &other)),
    }
}

pub(crate) fn session(handle: i64) -> NativeResult<SessionRef> {
    match get(handle)? {
        Object::Session(session) => Ok(session),
        other => Err(wrong_type("Session", &other)),
    }
}

pub(crate) fn extension(handle: i64) -> NativeResult<Arc<dyn Any + Send + Sync>> {
    match get(handle)? {
        Object::Extension(object) => Ok(object),
        other => Err(wrong_type("extension", &other)),
    }
}

pub(crate) fn store(handle: i64) -> NativeResult<StoreRef> {
    match get(handle)? {
        Object::Store(store) => Ok(store),
        other => Err(wrong_type("Store", &other)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_decode_roundtrip() {
        for (index, generation) in [(0, 1), (7, 3), (u32::MAX, u32::MAX)] {
            let (i, g) = decode(encode(index, generation));
            assert_eq!((i, g), (index as usize, generation));
        }
    }

    #[test]
    fn zero_is_never_live() {
        assert_eq!(get(0).map(|_| ()).unwrap_err().kind, ErrorKind::Closed);
    }
}
