//! Credentials refreshed by a Java callback.
//!
//! `S3Credentials.refreshable` and `GcsCredentials.refreshable` wrap a Java `Supplier` in a
//! `CredentialsCallback` object. `Storage` passes that object to [`storageOpen`] next to the
//! JSON spec, which refers to it by index. Here it becomes icechunk's
//! `S3CredentialsFetcher` or `GcsCredentialsFetcher`, which icechunk calls whenever its
//! cached credentials are about to expire.
//!
//! icechunk calls the fetcher from whatever thread polls the request that needs
//! credentials: a Java thread inside `block_on`, or a runtime worker. Neither may run the
//! supplier, which can block on HTTP for seconds:
//!
//! - a runtime worker that blocks holds up every other call, and runtime threads stay
//!   detached from the JVM (see `dev/DESIGN.md`, "Calls");
//! - the Java thread is inside a native call, so a supplier that called back into icechunk
//!   would nest `block_on` and could deadlock.
//!
//! So the fetcher only hands the request to Java. It attaches the current thread for the
//! duration of one non-blocking call, `CredentialsCallback.request(id)`, which queues the
//! supplier on a Java executor and returns, then detaches, as a runtime thread does when
//! it releases a borrowed buffer. The fetcher then awaits a oneshot channel, without
//! blocking any thread. The executor runs the supplier and reports the result through
//! [`credentialsComplete`], an ordinary native call from a Java thread, which completes
//! the channel. A supplier that never answers is cut off after its timeout.
//!
//! [`storageOpen`]: crate::storage
//! [`credentialsComplete`]: credentialsComplete

use std::collections::HashMap;
use std::fmt;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError, Weak};
use std::time::Duration;

use async_trait::async_trait;
use icechunk::config::{
    GcsBearerCredential, GcsCredentialsFetcher, S3CredentialsFetcher, S3StaticCredentials,
};
use jni::objects::{Global, JObject, JObjectArray, JString, JValue};
use jni::sys::jlong;
use jni::{Env, JavaVM, jni_sig, jni_str};
use serde::{Deserialize, Serialize};
use tokio::sync::oneshot;

use crate::call::{native, optional_text};
use crate::error::{NativeError, NativeResult};
use crate::spec::{GcsCredentialsSpec, S3CredentialsSpec};

/// How long a fetch waits for the supplier when the Java side sets no timeout.
pub(crate) const DEFAULT_TIMEOUT: Duration = Duration::from_secs(120);

/// A Java `CredentialsCallback`, kept alive for as long as a fetcher refers to it.
///
/// The global reference keeps the Java object, and with it the class loader that loaded
/// the binding, reachable only while some storage uses these credentials. Dropping it on a
/// runtime thread attaches that thread for the duration of the drop.
pub(crate) struct JavaCallback {
    id: u64,
    object: Global<JObject<'static>>,
}

impl fmt::Debug for JavaCallback {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("JavaCallback").field("id", &self.id).finish_non_exhaustive()
    }
}

/// Callbacks by id, so that a fetcher deserialized in this process finds its callback.
/// Weak, so the registry never keeps a callback alive.
static CALLBACKS: Mutex<Option<HashMap<u64, Weak<JavaCallback>>>> = Mutex::new(None);
static NEXT_CALLBACK: AtomicU64 = AtomicU64::new(1);

/// Requests waiting for `credentialsComplete`.
type Reply = Result<String, String>;
static PENDING: Mutex<Option<HashMap<u64, oneshot::Sender<Reply>>>> = Mutex::new(None);
static NEXT_REQUEST: AtomicU64 = AtomicU64::new(1);

fn with_callbacks<T>(f: impl FnOnce(&mut HashMap<u64, Weak<JavaCallback>>) -> T) -> T {
    let mut guard = CALLBACKS.lock().unwrap_or_else(PoisonError::into_inner);
    f(guard.get_or_insert_with(HashMap::new))
}

fn with_pending<T>(f: impl FnOnce(&mut HashMap<u64, oneshot::Sender<Reply>>) -> T) -> T {
    let mut guard = PENDING.lock().unwrap_or_else(PoisonError::into_inner);
    f(guard.get_or_insert_with(HashMap::new))
}

impl JavaCallback {
    fn new(env: &Env<'_>, object: &JObject<'_>) -> NativeResult<Arc<Self>> {
        if object.is_null() {
            return Err(NativeError::invalid_argument("null credentials callback"));
        }
        let object = env.new_global_ref(object)?;
        let id = NEXT_CALLBACK.fetch_add(1, Ordering::Relaxed);
        let callback = Arc::new(JavaCallback { id, object });
        with_callbacks(|map| map.insert(id, Arc::downgrade(&callback)));
        Ok(callback)
    }

    fn find(id: u64) -> Option<Arc<Self>> {
        with_callbacks(|map| map.get(&id).and_then(Weak::upgrade))
    }

    /// Queue the supplier on the Java executor. Does not block on the supplier.
    fn submit(&self, request: u64) -> Result<(), String> {
        let vm = JavaVM::singleton().map_err(|err| format!("no JVM: {err}"))?;
        // Scoped: a runtime thread is attached only for this call, then detached again.
        vm.attach_current_thread_for_scope(|env| -> jni::errors::Result<()> {
            #[allow(clippy::cast_possible_wrap, reason = "request ids fit a long")]
            let request = request as jlong;
            env.call_method(
                &self.object,
                jni_str!("request"),
                jni_sig!("(J)V"),
                &[JValue::Long(request)],
            )?;
            Ok(())
        })
        .map_err(|err| format!("cannot run the credentials callback: {err}"))
    }
}

impl Drop for JavaCallback {
    fn drop(&mut self) {
        with_callbacks(|map| {
            // A newer callback never reuses an id, so this only removes our own entry.
            map.remove(&self.id);
        });
    }
}

/// Ask Java for credentials and wait for the JSON it answers with.
async fn request_json(callback: Option<&JavaCallback>, timeout: Duration) -> Reply {
    let callback = callback.ok_or_else(|| {
        "these refreshable credentials were created in another process; \
         their Java callback is not available here"
            .to_owned()
    })?;
    let id = NEXT_REQUEST.fetch_add(1, Ordering::Relaxed);
    let (sender, receiver) = oneshot::channel();
    with_pending(|pending| pending.insert(id, sender));
    if let Err(err) = callback.submit(id) {
        with_pending(|pending| pending.remove(&id));
        return Err(err);
    }
    match tokio::time::timeout(timeout, receiver).await {
        Ok(Ok(reply)) => reply,
        Ok(Err(_)) => Err("the credentials callback was dropped".to_owned()),
        Err(_) => {
            with_pending(|pending| pending.remove(&id));
            Err(format!("the credentials callback did not answer within {timeout:?}"))
        }
    }
}

/// What a fetcher serializes: enough to find its callback again in this process, and to
/// refuse to work in any other.
#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
struct FetcherState {
    callback_id: u64,
    pid: u32,
    timeout_ms: u64,
}

/// The fetcher icechunk holds for credentials refreshed by a Java callback.
#[derive(Clone)]
pub(crate) struct JavaFetcher {
    state: FetcherState,
    callback: Option<Arc<JavaCallback>>,
}

impl fmt::Debug for JavaFetcher {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("JavaFetcher")
            .field("callback_id", &self.state.callback_id)
            .finish()
    }
}

impl JavaFetcher {
    fn new(callback: Arc<JavaCallback>, timeout: Duration) -> Self {
        let state = FetcherState {
            callback_id: callback.id,
            pid: std::process::id(),
            timeout_ms: u64::try_from(timeout.as_millis()).unwrap_or(u64::MAX),
        };
        JavaFetcher { state, callback: Some(callback) }
    }

    async fn json(&self) -> Reply {
        request_json(
            self.callback.as_deref(),
            Duration::from_millis(self.state.timeout_ms),
        )
        .await
    }
}

impl From<JavaFetcher> for FetcherState {
    fn from(fetcher: JavaFetcher) -> Self {
        fetcher.state
    }
}

impl From<FetcherState> for JavaFetcher {
    fn from(state: FetcherState) -> Self {
        let callback = if state.pid == std::process::id() {
            JavaCallback::find(state.callback_id)
        } else {
            None
        };
        JavaFetcher { state, callback }
    }
}

/// S3 credentials from a Java supplier.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(from = "FetcherState", into = "FetcherState")]
pub(crate) struct JavaS3Fetcher(JavaFetcher);

impl From<FetcherState> for JavaS3Fetcher {
    fn from(state: FetcherState) -> Self {
        JavaS3Fetcher(state.into())
    }
}

impl From<JavaS3Fetcher> for FetcherState {
    fn from(fetcher: JavaS3Fetcher) -> Self {
        fetcher.0.into()
    }
}

#[async_trait]
#[typetag::serde(name = "icechunk_java")]
impl S3CredentialsFetcher for JavaS3Fetcher {
    async fn get(&self) -> Result<S3StaticCredentials, String> {
        let json = self.0.json().await?;
        let spec: S3CredentialsSpec = serde_json::from_str(&json).map_err(|err| {
            format!("the credentials callback returned bad JSON: {err}")
        })?;
        spec.into_static().ok_or_else(|| {
            "a refreshable S3 credentials supplier must return S3Credentials.of(...)"
                .to_owned()
        })
    }
}

/// GCS bearer tokens from a Java supplier.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(from = "FetcherState", into = "FetcherState")]
pub(crate) struct JavaGcsFetcher(JavaFetcher);

impl From<FetcherState> for JavaGcsFetcher {
    fn from(state: FetcherState) -> Self {
        JavaGcsFetcher(state.into())
    }
}

impl From<JavaGcsFetcher> for FetcherState {
    fn from(fetcher: JavaGcsFetcher) -> Self {
        fetcher.0.into()
    }
}

#[async_trait]
#[typetag::serde(name = "icechunk_java")]
impl GcsCredentialsFetcher for JavaGcsFetcher {
    async fn get(&self) -> Result<GcsBearerCredential, String> {
        let json = self.0.json().await?;
        let spec: GcsCredentialsSpec = serde_json::from_str(&json).map_err(|err| {
            format!("the credentials callback returned bad JSON: {err}")
        })?;
        spec.into_bearer().ok_or_else(|| {
            "a refreshable GCS credentials supplier must return GcsCredentials.bearerToken(...)"
                .to_owned()
        })
    }
}

/// The Java callbacks a storage spec refers to by index.
#[derive(Debug, Default)]
pub(crate) struct Callbacks(Vec<Arc<JavaCallback>>);

impl Callbacks {
    /// Take global references to the elements of `array`, which may be null.
    pub(crate) fn from_array(
        env: &mut Env<'_>,
        array: &JObjectArray<'_>,
    ) -> NativeResult<Self> {
        if array.is_null() {
            return Ok(Callbacks::default());
        }
        let len = array.len(env)?;
        let mut callbacks = Vec::with_capacity(len);
        for index in 0..len {
            let element = array.get_element(env, index)?;
            callbacks.push(JavaCallback::new(env, &element)?);
            env.delete_local_ref(element);
        }
        Ok(Callbacks(callbacks))
    }

    fn get(&self, index: usize) -> NativeResult<Arc<JavaCallback>> {
        self.0.get(index).cloned().ok_or_else(|| {
            NativeError::invalid_argument(format!(
                "no credentials callback number {index}"
            ))
        })
    }

    pub(crate) fn s3_fetcher(
        &self,
        index: usize,
        timeout: Duration,
    ) -> NativeResult<Arc<dyn S3CredentialsFetcher>> {
        Ok(Arc::new(JavaS3Fetcher(JavaFetcher::new(self.get(index)?, timeout))))
    }

    pub(crate) fn gcs_fetcher(
        &self,
        index: usize,
        timeout: Duration,
    ) -> NativeResult<Arc<dyn GcsCredentialsFetcher>> {
        Ok(Arc::new(JavaGcsFetcher(JavaFetcher::new(self.get(index)?, timeout))))
    }
}

native! { fn credentialsComplete(env, request: jlong, json: JString<'l>, error: JString<'l>) -> () {
    let json = optional_text(env, &json)?;
    let error = optional_text(env, &error)?;
    let reply = match (json, error) {
        (_, Some(error)) => Err(error),
        (Some(json), None) => Ok(json),
        (None, None) => Err("the credentials callback returned nothing".to_owned()),
    };
    #[allow(clippy::cast_sign_loss, reason = "request ids are handed out as longs")]
    let request = request as u64;
    if let Some(sender) = with_pending(|pending| pending.remove(&request)) {
        // The fetch may have timed out and dropped the receiver; nothing to do then.
        let _ = sender.send(reply);
    }
    Ok(())
}}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fetcher_from_another_process_refuses_to_fetch() {
        let state =
            FetcherState { callback_id: 42, pid: std::process::id() + 1, timeout_ms: 10 };
        let fetcher = JavaFetcher::from(state);
        assert!(fetcher.callback.is_none());
        let reply = futures::executor::block_on(fetcher.json());
        assert!(reply.is_err_and(|err| err.contains("another process")));
    }

    #[test]
    fn fetchers_serialize_only_their_id_and_process() {
        let state = FetcherState { callback_id: 7, pid: 1, timeout_ms: 5000 };
        let fetcher = JavaS3Fetcher(JavaFetcher::from(state));
        let fetcher: Arc<dyn S3CredentialsFetcher> = Arc::new(fetcher);
        let json = serde_json::to_string(&fetcher).unwrap_or_default();
        assert_eq!(
            json,
            r#"{"s3_credentials_fetcher_type":"icechunk_java","callback_id":7,"pid":1,"timeout_ms":5000}"#
        );
        let back: Arc<dyn S3CredentialsFetcher> = serde_json::from_str(&json)
            .unwrap_or_else(|_| Arc::new(JavaS3Fetcher(state.into())));
        assert!(format!("{back:?}").contains("callback_id: 7"));
    }

    #[test]
    fn completing_an_unknown_request_is_harmless() {
        assert!(with_pending(|pending| pending.remove(&u64::MAX)).is_none());
    }
}
