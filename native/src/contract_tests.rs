//! Checks that the constants and error kinds shared with the Java side agree.
//!
//! Both sides define them, in `Native.java` and `IcechunkException.java`, so a change on
//! one side alone would otherwise compile and fail only at run time.

use std::collections::HashMap;

use crate::error::ErrorKind;
use crate::{repository, store};

const NATIVE_JAVA: &str =
    include_str!("../../icechunk-java/src/main/java/io/earthmover/icechunk/Native.java");
const EXCEPTION_JAVA: &str = include_str!(
    "../../icechunk-java/src/main/java/io/earthmover/icechunk/IcechunkException.java"
);

/// `static final int NAME = value;` and `static final long NAME = value;` declarations.
fn java_constants() -> HashMap<String, i64> {
    NATIVE_JAVA
        .lines()
        .filter_map(|line| {
            let rest = line.trim().strip_prefix("static final ")?;
            let rest =
                rest.strip_prefix("int ").or_else(|| rest.strip_prefix("long "))?;
            let (name, value) = rest.strip_suffix(';')?.split_once(" = ")?;
            Some((name.to_owned(), value.parse().ok()?))
        })
        .collect()
}

#[test]
fn constants_match_native_java() {
    let rust: &[(&str, i64)] = &[
        ("OPEN", repository::OPEN.into()),
        ("CREATE", repository::CREATE.into()),
        ("OPEN_OR_CREATE", repository::OPEN_OR_CREATE.into()),
        ("VERSION_BRANCH", repository::VERSION_BRANCH.into()),
        ("VERSION_TAG", repository::VERSION_TAG.into()),
        ("VERSION_SNAPSHOT", repository::VERSION_SNAPSHOT.into()),
        ("RANGE_BOUNDED", store::RANGE_BOUNDED),
        ("RANGE_FROM", store::RANGE_FROM),
        ("RANGE_SUFFIX", store::RANGE_SUFFIX),
        ("LIST_ALL", store::LIST_ALL.into()),
        ("LIST_PREFIX", store::LIST_PREFIX.into()),
        ("LIST_DIR", store::LIST_DIR.into()),
    ];
    let java = java_constants();
    for (name, value) in rust {
        assert_eq!(
            java.get(*name),
            Some(value),
            "Native.{name} differs from the Rust value"
        );
    }
    assert_eq!(java.len(), rust.len(), "Native.java has constants the Rust side lacks");
}

#[test]
fn error_kinds_match_icechunk_exception() {
    let java_cases: Vec<&str> = EXCEPTION_JAVA
        .lines()
        .filter_map(|line| line.trim().strip_prefix("case \"")?.strip_suffix("\":"))
        .collect();
    // Kinds without a case fall through to a plain IcechunkException.
    let mapped = [
        ErrorKind::Conflict,
        ErrorKind::InvalidArgument,
        ErrorKind::Closed,
        ErrorKind::RuntimeThread,
    ];
    for kind in mapped {
        assert!(java_cases.contains(&kind.as_str()), "no Java case for {kind:?}");
    }
    let all = [
        ErrorKind::Icechunk,
        ErrorKind::Conflict,
        ErrorKind::InvalidArgument,
        ErrorKind::Closed,
        ErrorKind::RuntimeThread,
        ErrorKind::Jni,
    ];
    for case in java_cases {
        assert!(
            all.iter().any(|k| k.as_str() == case),
            "Java case {case:?} matches no ErrorKind"
        );
    }
}
