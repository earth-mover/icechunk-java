use std::fmt;

use icechunk::ops::gc::GCError;
use icechunk::refs::{RefError, RefErrorKind};
use icechunk::repository::{RepositoryError, RepositoryErrorKind};
use icechunk::session::{SessionError, SessionErrorKind};
use icechunk::storage::StorageError;
use icechunk::store::{StoreError, StoreErrorKind};

/// Error categories the Java side maps to exception classes.
///
/// The string forms are part of the contract with `IcechunkException.fromNative` in
/// Java; change both together.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    /// Any icechunk error without a more specific category.
    Icechunk,
    /// A commit lost a race with another writer on the same branch.
    Conflict,
    /// The Java caller passed a value the native layer cannot use.
    InvalidArgument,
    /// The handle was closed, or never referred to a live object.
    Closed,
    /// A blocking call was made from one of the runtime's own threads.
    RuntimeThread,
    /// A JNI call failed.
    Jni,
}

impl ErrorKind {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            ErrorKind::Icechunk => "ICECHUNK",
            ErrorKind::Conflict => "CONFLICT",
            ErrorKind::InvalidArgument => "INVALID_ARGUMENT",
            ErrorKind::Closed => "CLOSED",
            ErrorKind::RuntimeThread => "RUNTIME_THREAD",
            ErrorKind::Jni => "JNI",
        }
    }
}

#[derive(Debug)]
pub struct NativeError {
    pub kind: ErrorKind,
    pub message: String,
}

pub type NativeResult<T> = Result<T, NativeError>;

impl NativeError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into() }
    }

    pub fn invalid_argument(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::InvalidArgument, message)
    }

    fn icechunk(err: &impl fmt::Display) -> Self {
        Self::new(ErrorKind::Icechunk, err.to_string())
    }
}

impl fmt::Display for NativeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.kind.as_str(), self.message)
    }
}

impl std::error::Error for NativeError {}

impl From<jni::errors::Error> for NativeError {
    fn from(err: jni::errors::Error) -> Self {
        Self::new(ErrorKind::Jni, err.to_string())
    }
}

impl From<serde_json::Error> for NativeError {
    fn from(err: serde_json::Error) -> Self {
        Self::invalid_argument(format!("invalid JSON document from Java: {err}"))
    }
}

impl From<RepositoryError> for NativeError {
    fn from(err: RepositoryError) -> Self {
        match &err.kind {
            // Spec version 1 repositories report a moved branch as a ref conflict.
            RepositoryErrorKind::Conflict { .. }
            | RepositoryErrorKind::Ref(RefErrorKind::Conflict { .. }) => {
                Self::new(ErrorKind::Conflict, err.to_string())
            }
            _ => Self::icechunk(&err),
        }
    }
}

impl From<RefError> for NativeError {
    fn from(err: RefError) -> Self {
        match &err.kind {
            RefErrorKind::Conflict { .. } => {
                Self::new(ErrorKind::Conflict, err.to_string())
            }
            _ => Self::icechunk(&err),
        }
    }
}

impl From<SessionError> for NativeError {
    fn from(err: SessionError) -> Self {
        match &err.kind {
            SessionErrorKind::Conflict { .. } | SessionErrorKind::RebaseFailed { .. } => {
                Self::new(ErrorKind::Conflict, err.to_string())
            }
            _ => Self::icechunk(&err),
        }
    }
}

impl From<StoreError> for NativeError {
    fn from(err: StoreError) -> Self {
        match &err.kind {
            StoreErrorKind::SessionError(SessionErrorKind::Conflict { .. }) => {
                Self::new(ErrorKind::Conflict, err.to_string())
            }
            _ => Self::icechunk(&err),
        }
    }
}

impl From<StorageError> for NativeError {
    fn from(err: StorageError) -> Self {
        Self::icechunk(&err)
    }
}

impl From<GCError> for NativeError {
    fn from(err: GCError) -> Self {
        match err {
            GCError::Ref(err) => err.into(),
            GCError::Repository(err) => err.into(),
            GCError::StorageError(err) => err.into(),
            GCError::FormatError(err) => Self::icechunk(&err),
        }
    }
}
