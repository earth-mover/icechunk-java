//! The JSON documents Java sends: storage, credentials, repository options, versions, and
//! commit, expiration and garbage collection options.
//!
//! These types are the binding's own, not icechunk's serde types. Several inputs have no
//! serde form in icechunk (`VersionInfo`, `GCConfig`, commit options). icechunk's
//! credential serde exists so icechunk-python can pickle credentials, includes typetag
//! tags for refreshable credential fetchers, and changed in a patch release (v1.0.2
//! re-tagged GCS and Azure static credentials, icechunk#1109). Every field name here must
//! match what the Java builders emit; `deny_unknown_fields` rejects a misspelled field
//! instead of ignoring it.

use std::collections::{HashMap, HashSet};
use std::num::{NonZeroU16, NonZeroUsize};
use std::path::PathBuf;

use chrono::{DateTime, Utc};
use icechunk::RepositoryConfig;
use icechunk::config::{
    AzureCredentials, AzureStaticCredentials, Credentials, GcsBearerCredential,
    GcsCredentials, GcsStaticCredentials, S3Credentials, S3Options, S3StaticCredentials,
};
use serde::Deserialize;

use icechunk::format::SnapshotId;
use icechunk::format::format_constants::SpecVersionBin;
use icechunk::format::snapshot::SnapshotProperties;
use icechunk::ops::gc::{Action, GCConfig};
use icechunk::repository::VersionInfo;

use crate::error::{NativeError, NativeResult};

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum StorageSpec {
    InMemory,
    LocalFilesystem {
        path: PathBuf,
    },
    S3 {
        bucket: String,
        prefix: Option<String>,
        #[serde(default)]
        options: S3OptionsSpec,
        #[serde(default)]
        credentials: S3CredentialsSpec,
    },
    Gcs {
        bucket: String,
        prefix: Option<String>,
        #[serde(default)]
        credentials: GcsCredentialsSpec,
        #[serde(default)]
        config: HashMap<String, String>,
    },
    Azure {
        account: String,
        container: String,
        prefix: Option<String>,
        #[serde(default)]
        credentials: AzureCredentialsSpec,
        #[serde(default)]
        config: HashMap<String, String>,
    },
    Http {
        url: String,
        #[serde(default)]
        config: HashMap<String, String>,
    },
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub(crate) struct S3OptionsSpec {
    region: Option<String>,
    endpoint_url: Option<String>,
    allow_http: bool,
    force_path_style: bool,
    network_stream_timeout_seconds: Option<u32>,
    requester_pays: bool,
}

impl S3OptionsSpec {
    pub(crate) fn into_options(self, credentials: &S3CredentialsSpec) -> S3Options {
        let mut options = S3Options::default();
        options.region = self.region;
        options.endpoint_url = self.endpoint_url;
        options.anonymous = matches!(credentials, S3CredentialsSpec::Anonymous);
        options.allow_http = self.allow_http;
        options.force_path_style = self.force_path_style;
        options.network_stream_timeout_seconds = self.network_stream_timeout_seconds;
        options.requester_pays = self.requester_pays;
        options
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum S3CredentialsSpec {
    #[default]
    FromEnv,
    Anonymous,
    Static {
        access_key_id: String,
        secret_access_key: String,
        session_token: Option<String>,
    },
}

impl From<S3CredentialsSpec> for S3Credentials {
    fn from(spec: S3CredentialsSpec) -> Self {
        match spec {
            S3CredentialsSpec::FromEnv => S3Credentials::FromEnv,
            S3CredentialsSpec::Anonymous => S3Credentials::Anonymous,
            S3CredentialsSpec::Static {
                access_key_id,
                secret_access_key,
                session_token,
            } => S3Credentials::Static(S3StaticCredentials {
                access_key_id,
                secret_access_key,
                session_token,
                expires_after: None,
            }),
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum GcsCredentialsSpec {
    #[default]
    FromEnv,
    Anonymous,
    ServiceAccountFile {
        path: PathBuf,
    },
    ServiceAccountKey {
        key: String,
    },
    ApplicationCredentialsFile {
        path: PathBuf,
    },
    BearerToken {
        token: String,
    },
}

impl From<GcsCredentialsSpec> for GcsCredentials {
    fn from(spec: GcsCredentialsSpec) -> Self {
        match spec {
            GcsCredentialsSpec::FromEnv => GcsCredentials::FromEnv,
            GcsCredentialsSpec::Anonymous => GcsCredentials::Anonymous,
            GcsCredentialsSpec::ServiceAccountFile { path } => {
                GcsCredentials::Static(GcsStaticCredentials::ServiceAccount(path))
            }
            GcsCredentialsSpec::ServiceAccountKey { key } => {
                GcsCredentials::Static(GcsStaticCredentials::ServiceAccountKey(key))
            }
            GcsCredentialsSpec::ApplicationCredentialsFile { path } => {
                GcsCredentials::Static(GcsStaticCredentials::ApplicationCredentials(path))
            }
            GcsCredentialsSpec::BearerToken { token } => {
                GcsCredentials::Static(GcsStaticCredentials::BearerToken(
                    GcsBearerCredential { bearer: token, expires_after: None },
                ))
            }
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum AzureCredentialsSpec {
    #[default]
    FromEnv,
    Anonymous,
    AccessKey {
        key: String,
    },
    SasToken {
        token: String,
    },
    BearerToken {
        token: String,
    },
}

impl From<AzureCredentialsSpec> for AzureCredentials {
    fn from(spec: AzureCredentialsSpec) -> Self {
        match spec {
            AzureCredentialsSpec::FromEnv => AzureCredentials::FromEnv,
            AzureCredentialsSpec::Anonymous => AzureCredentials::Anonymous,
            AzureCredentialsSpec::AccessKey { key } => {
                AzureCredentials::Static(AzureStaticCredentials::AccessKey(key))
            }
            AzureCredentialsSpec::SasToken { token } => {
                AzureCredentials::Static(AzureStaticCredentials::SASToken(token))
            }
            AzureCredentialsSpec::BearerToken { token } => {
                AzureCredentials::Static(AzureStaticCredentials::BearerToken(token))
            }
        }
    }
}

/// Credentials for one virtual chunk container.
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum ContainerCredentialsSpec {
    S3 { credentials: S3CredentialsSpec },
    Gcs { credentials: GcsCredentialsSpec },
    Azure { credentials: AzureCredentialsSpec },
    LocalFilesystem,
    Http,
}

impl From<ContainerCredentialsSpec> for Credentials {
    fn from(spec: ContainerCredentialsSpec) -> Self {
        match spec {
            ContainerCredentialsSpec::S3 { credentials } => {
                Credentials::S3(credentials.into())
            }
            ContainerCredentialsSpec::Gcs { credentials } => {
                Credentials::Gcs(credentials.into())
            }
            ContainerCredentialsSpec::Azure { credentials } => {
                Credentials::Azure(credentials.into())
            }
            ContainerCredentialsSpec::LocalFilesystem => {
                Credentials::LocalFileSystemAccess
            }
            ContainerCredentialsSpec::Http => Credentials::HttpAccess,
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub(crate) struct RepositoryOptionsSpec {
    /// icechunk's own `RepositoryConfig` document, passed through unchanged. icechunk
    /// persists this format in every repository, so it keeps reading older documents.
    config: Option<serde_json::Value>,
    virtual_chunk_credentials: HashMap<String, ContainerCredentialsSpec>,
    spec_version: Option<u8>,
    check_clean_root: Option<bool>,
}

#[derive(Debug)]
pub struct RepositoryOptions {
    pub config: Option<RepositoryConfig>,
    pub virtual_chunk_credentials: HashMap<String, Option<Credentials>>,
    pub spec_version: Option<SpecVersionBin>,
    pub check_clean_root: bool,
}

impl RepositoryOptionsSpec {
    pub(crate) fn parse(json: &str) -> NativeResult<RepositoryOptions> {
        let spec: RepositoryOptionsSpec = serde_json::from_str(json)?;
        let config = spec.config.map(serde_json::from_value).transpose()?;
        Ok(RepositoryOptions {
            config,
            virtual_chunk_credentials: spec
                .virtual_chunk_credentials
                .into_iter()
                .map(|(prefix, credentials)| (prefix, Some(credentials.into())))
                .collect(),
            spec_version: spec
                .spec_version
                .map(|v| {
                    SpecVersionBin::try_from(v).map_err(|err| {
                        NativeError::invalid_argument(format!(
                            "unsupported spec version {v}: {err}"
                        ))
                    })
                })
                .transpose()?,
            check_clean_root: spec.check_clean_root.unwrap_or(true),
        })
    }
}

pub(crate) fn snapshot_id(id: &str) -> NativeResult<SnapshotId> {
    SnapshotId::try_from(id).map_err(|err| {
        NativeError::invalid_argument(format!("bad snapshot id {id:?}: {err}"))
    })
}

/// A `Version` from Java, one variant per `VersionInfo` variant.
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum VersionSpec {
    Branch {
        name: String,
    },
    Tag {
        name: String,
    },
    SnapshotId {
        id: String,
    },
    /// `at` is an RFC 3339 timestamp, as `java.time.Instant.toString` writes it.
    AsOf {
        branch: String,
        at: DateTime<Utc>,
    },
}

impl VersionSpec {
    pub(crate) fn parse(json: &str) -> NativeResult<VersionInfo> {
        match serde_json::from_str(json)? {
            VersionSpec::Branch { name } => Ok(VersionInfo::BranchTipRef(name)),
            VersionSpec::Tag { name } => Ok(VersionInfo::TagRef(name)),
            VersionSpec::SnapshotId { id } => {
                Ok(VersionInfo::SnapshotId(snapshot_id(&id)?))
            }
            VersionSpec::AsOf { branch, at } => Ok(VersionInfo::AsOf { branch, at }),
        }
    }
}

/// `CommitOptions` from Java.
#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub(crate) struct CommitSpec {
    pub(crate) metadata: Option<SnapshotProperties>,
    pub(crate) allow_empty: bool,
}

impl CommitSpec {
    pub(crate) fn parse(json: &str) -> NativeResult<CommitSpec> {
        Ok(serde_json::from_str(json)?)
    }
}

/// `ExpireOptions` from Java, with the cutoff `Repository.expireSnapshots` adds.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ExpireSpec {
    pub(crate) older_than: DateTime<Utc>,
    pub(crate) delete_expired_branches: bool,
    pub(crate) delete_expired_tags: bool,
}

impl ExpireSpec {
    pub(crate) fn parse(json: &str) -> NativeResult<ExpireSpec> {
        Ok(serde_json::from_str(json)?)
    }
}

/// `GcOptions` from Java. An absent cutoff keeps every object of that kind.
///
/// icechunk's `GCConfig` also has a cutoff for attribute files, which icechunk 2.2 never
/// deletes, so it is always `Action::Keep`.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct GcSpec {
    extra_roots: Vec<String>,
    delete_chunks_older_than: Option<DateTime<Utc>>,
    delete_manifests_older_than: Option<DateTime<Utc>>,
    delete_transaction_logs_older_than: Option<DateTime<Utc>>,
    delete_snapshots_older_than: Option<DateTime<Utc>>,
    max_snapshots_in_memory: NonZeroU16,
    max_compressed_manifest_mem_bytes: NonZeroUsize,
    max_concurrent_manifest_fetches: NonZeroU16,
    dry_run: bool,
}

impl GcSpec {
    pub(crate) fn parse(json: &str) -> NativeResult<GCConfig> {
        let spec: GcSpec = serde_json::from_str(json)?;
        let action = |cutoff: Option<DateTime<Utc>>| {
            cutoff.map_or(Action::Keep, Action::DeleteIfCreatedBefore)
        };
        let extra_roots = spec
            .extra_roots
            .iter()
            .map(|id| snapshot_id(id))
            .collect::<NativeResult<HashSet<_>>>()?;
        Ok(GCConfig::new(
            extra_roots,
            action(spec.delete_chunks_older_than),
            action(spec.delete_manifests_older_than),
            Action::Keep,
            action(spec.delete_transaction_logs_older_than),
            action(spec.delete_snapshots_older_than),
            spec.max_snapshots_in_memory,
            spec.max_compressed_manifest_mem_bytes,
            spec.max_concurrent_manifest_fetches,
            spec.dry_run,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The JSON literals below are what the Java builders emit; `JsonContractTest` checks
    // the Java side produces the same strings.

    #[test]
    fn local_filesystem() {
        let spec: StorageSpec =
            serde_json::from_str(r#"{"type":"local_filesystem","path":"/tmp/repo"}"#)
                .unwrap();
        assert!(
            matches!(spec, StorageSpec::LocalFilesystem { path } if path == std::path::Path::new("/tmp/repo"))
        );
    }

    #[test]
    fn s3_with_static_credentials() {
        let json = r#"{"type":"s3","bucket":"b","prefix":"p",
            "options":{"region":"us-east-1","endpoint_url":"http://localhost:9000",
                       "allow_http":true,"force_path_style":true},
            "credentials":{"type":"static","access_key_id":"a","secret_access_key":"s"}}"#;
        let StorageSpec::S3 { bucket, prefix, options, credentials } =
            serde_json::from_str(json).unwrap()
        else {
            panic!("not an s3 spec");
        };
        assert_eq!(bucket, "b");
        assert_eq!(prefix.as_deref(), Some("p"));
        let options = options.into_options(&credentials);
        assert_eq!(options.region.as_deref(), Some("us-east-1"));
        assert!(options.allow_http && options.force_path_style && !options.anonymous);
        assert!(matches!(
            S3Credentials::from(credentials),
            S3Credentials::Static(S3StaticCredentials { session_token: None, .. })
        ));
    }

    #[test]
    fn s3_anonymous_sets_the_option() {
        let json = r#"{"type":"s3","bucket":"b","credentials":{"type":"anonymous"}}"#;
        let StorageSpec::S3 { options, credentials, .. } =
            serde_json::from_str(json).unwrap()
        else {
            panic!("not an s3 spec");
        };
        assert!(options.into_options(&credentials).anonymous);
    }

    #[test]
    fn unknown_fields_are_rejected() {
        let json = r#"{"type":"s3","bucket":"b","options":{"regoin":"x"}}"#;
        assert!(serde_json::from_str::<StorageSpec>(json).is_err());
    }

    #[test]
    fn repository_options() {
        let json = r#"{"config":{"inline_chunk_threshold_bytes":12},
            "virtual_chunk_credentials":{
                "s3://bucket/":{"type":"s3","credentials":{"type":"anonymous"}},
                "file:///data/":{"type":"local_filesystem"}},
            "check_clean_root":false}"#;
        let options = RepositoryOptionsSpec::parse(json).unwrap();
        assert_eq!(options.config.and_then(|c| c.inline_chunk_threshold_bytes), Some(12));
        assert!(!options.check_clean_root);
        assert!(matches!(
            options.virtual_chunk_credentials.get("file:///data/"),
            Some(Some(Credentials::LocalFileSystemAccess))
        ));
    }

    #[test]
    fn empty_repository_options() {
        let options = RepositoryOptionsSpec::parse("{}").unwrap();
        assert!(options.config.is_none());
        assert!(options.check_clean_root);
    }

    #[test]
    fn versions() {
        assert!(matches!(
            VersionSpec::parse(r#"{"type":"branch","name":"main"}"#).unwrap(),
            VersionInfo::BranchTipRef(name) if name == "main"
        ));
        assert!(matches!(
            VersionSpec::parse(r#"{"type":"tag","name":"v1"}"#).unwrap(),
            VersionInfo::TagRef(name) if name == "v1"
        ));
        assert!(matches!(
            VersionSpec::parse(r#"{"type":"snapshot_id","id":"1CECHNKREP0F1RSTCMT0"}"#)
                .unwrap(),
            VersionInfo::SnapshotId(_)
        ));
        let VersionInfo::AsOf { branch, at } = VersionSpec::parse(
            r#"{"type":"as_of","branch":"main","at":"2026-01-02T03:04:05.000006Z"}"#,
        )
        .unwrap() else {
            panic!("expected AsOf");
        };
        assert_eq!(branch, "main");
        assert_eq!(at.to_rfc3339(), "2026-01-02T03:04:05.000006+00:00");
    }

    #[test]
    fn bad_versions_are_rejected() {
        assert!(VersionSpec::parse(r#"{"type":"snapshot_id","id":"nope"}"#).is_err());
        assert!(
            VersionSpec::parse(r#"{"type":"as_of","branch":"main","at":"yesterday"}"#)
                .is_err()
        );
    }

    #[test]
    fn commit_options() {
        let spec = CommitSpec::parse(
            r#"{"metadata":{"author":"ian","n":1},"allow_empty":true}"#,
        )
        .unwrap();
        assert!(spec.allow_empty);
        let metadata = spec.metadata.unwrap();
        assert_eq!(metadata.get("author"), Some(&serde_json::json!("ian")));
        assert_eq!(metadata.get("n"), Some(&serde_json::json!(1)));
        let defaults = CommitSpec::parse(r#"{"allow_empty":false}"#).unwrap();
        assert!(defaults.metadata.is_none() && !defaults.allow_empty);
    }

    #[test]
    fn expire_options() {
        let spec = ExpireSpec::parse(
            r#"{"older_than":"2026-01-02T03:04:05Z","delete_expired_branches":true,"delete_expired_tags":false}"#,
        )
        .unwrap();
        assert_eq!(spec.older_than.to_rfc3339(), "2026-01-02T03:04:05+00:00");
        assert!(spec.delete_expired_branches && !spec.delete_expired_tags);
    }

    #[test]
    fn gc_options() {
        let config = GcSpec::parse(
            r#"{"extra_roots":["1CECHNKREP0F1RSTCMT0"],"delete_chunks_older_than":"2026-01-02T03:04:05Z","delete_snapshots_older_than":"2026-01-01T00:00:00Z","max_snapshots_in_memory":50,"max_compressed_manifest_mem_bytes":536870912,"max_concurrent_manifest_fetches":500,"dry_run":true}"#,
        )
        .unwrap();
        assert!(config.deletes_chunks() && config.deletes_snapshots());
        assert!(!config.deletes_manifests() && !config.deletes_transaction_logs());
        assert!(!config.deletes_attributes());
    }

    #[test]
    fn bad_gc_options_are_rejected() {
        let limits = r#""max_compressed_manifest_mem_bytes":1,"max_concurrent_manifest_fetches":1,"dry_run":false"#;
        let zero =
            format!(r#"{{"extra_roots":[],"max_snapshots_in_memory":0,{limits}}}"#);
        assert!(GcSpec::parse(&zero).is_err());
        let bad_root =
            format!(r#"{{"extra_roots":["nope"],"max_snapshots_in_memory":1,{limits}}}"#);
        assert!(GcSpec::parse(&bad_root).is_err());
    }
}
