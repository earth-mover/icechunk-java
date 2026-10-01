//! The JSON documents Java sends to describe storage, credentials and repository options.
//!
//! These types are the wire contract with the `io.earthmover.icechunk` builders. They
//! are deliberately separate from icechunk's own serde types, whose shapes follow
//! icechunk's persistence needs and can change between releases. Every field name here
//! must match what the Java builders emit; `deny_unknown_fields` turns a mismatch into
//! an error instead of a silently ignored option.

use std::collections::HashMap;
use std::path::PathBuf;

use icechunk::RepositoryConfig;
use icechunk::config::{
    AzureCredentials, AzureStaticCredentials, Credentials, GcsBearerCredential,
    GcsCredentials, GcsStaticCredentials, S3Credentials, S3Options, S3StaticCredentials,
};
use serde::Deserialize;

use crate::error::NativeResult;

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
    /// icechunk's own `RepositoryConfig` document, passed through unchanged. Its shape is
    /// icechunk's persisted config format, which is already stable across releases.
    config: Option<serde_json::Value>,
    virtual_chunk_credentials: HashMap<String, ContainerCredentialsSpec>,
    spec_version: Option<u8>,
    check_clean_root: Option<bool>,
}

#[derive(Debug)]
pub struct RepositoryOptions {
    pub config: Option<RepositoryConfig>,
    pub virtual_chunk_credentials: HashMap<String, Option<Credentials>>,
    pub spec_version: Option<u8>,
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
            spec_version: spec.spec_version,
            check_clean_root: spec.check_clean_root.unwrap_or(true),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The JSON literals below are what the Java builders emit; see the matching tests in
    // `StorageTest` and `RepositoryOptionsTest`.

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
}
