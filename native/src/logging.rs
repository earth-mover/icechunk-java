//! The native method behind `Logging.initialize`, which configures icechunk's own log
//! output.

use jni::objects::JString;

use tracing_subscriber::EnvFilter;

use crate::call::{native, optional_text};
use crate::error::NativeError;

// A null filter reads `ICECHUNK_LOG`. Calling again replaces the filter.
native! { fn initializeLogs(env, filter: JString<'l>) -> () {
    let filter = optional_text(env, &filter)?;
    // icechunk ignores directives it cannot parse, printing only a line to stderr, so
    // check them here to throw instead.
    if let Some(filter) = &filter {
        EnvFilter::try_new(filter).map_err(|err| {
            NativeError::invalid_argument(format!("bad log filter {filter:?}: {err}"))
        })?;
    }
    icechunk::initialize_tracing(filter.as_deref());
    Ok(())
}}
