//! The JSON documents native methods return to Java for structured results.
//!
//! Like the documents in `spec`, these are a wire contract with the Java classes that
//! read them (`SnapshotInfo`, `Diff`, `ExpireResult`, `GcSummary`), not icechunk's own
//! serde formats. Histories and diffs borrow from icechunk's result and are written
//! straight to the output, so a long history or a large diff is not copied into an
//! intermediate tree.

use std::collections::{BTreeMap, BTreeSet, HashSet};

use chrono::SecondsFormat;
use icechunk::diff::Diff;
use icechunk::format::snapshot::{SnapshotInfo, SnapshotProperties};
use icechunk::format::{ChunkIndices, Path, SnapshotId};
use icechunk::ops::gc::{ExpireResult, GCSummary};
use icechunk::refs::Ref;
use serde::Serialize;
use serde::ser::Serializer;

#[derive(Debug, Serialize)]
pub(crate) struct SnapshotInfoResult<'a> {
    id: String,
    parent_id: Option<String>,
    /// RFC 3339 in UTC with microseconds, which `Instant.parse` reads.
    flushed_at: String,
    message: &'a str,
    metadata: &'a SnapshotProperties,
}

impl<'a> From<&'a SnapshotInfo> for SnapshotInfoResult<'a> {
    fn from(snapshot: &'a SnapshotInfo) -> Self {
        Self {
            id: snapshot.id.to_string(),
            parent_id: snapshot.parent_id.as_ref().map(ToString::to_string),
            flushed_at: snapshot.flushed_at.to_rfc3339_opts(SecondsFormat::Micros, true),
            message: &snapshot.message,
            metadata: &snapshot.metadata,
        }
    }
}

/// A history, written element by element.
#[derive(Debug)]
pub(crate) struct SnapshotInfosResult<'a>(pub(crate) &'a [SnapshotInfo]);

impl Serialize for SnapshotInfosResult<'_> {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.collect_seq(self.0.iter().map(SnapshotInfoResult::from))
    }
}

/// Paths are absolute, as icechunk writes them (`/group/array`).
#[derive(Debug, Serialize)]
pub(crate) struct DiffResult<'a> {
    #[serde(serialize_with = "paths")]
    new_groups: &'a BTreeSet<Path>,
    #[serde(serialize_with = "paths")]
    new_arrays: &'a BTreeSet<Path>,
    #[serde(serialize_with = "paths")]
    deleted_groups: &'a BTreeSet<Path>,
    #[serde(serialize_with = "paths")]
    deleted_arrays: &'a BTreeSet<Path>,
    #[serde(serialize_with = "paths")]
    updated_groups: &'a BTreeSet<Path>,
    #[serde(serialize_with = "paths")]
    updated_arrays: &'a BTreeSet<Path>,
    #[serde(serialize_with = "chunks")]
    updated_chunks: &'a BTreeMap<Path, BTreeSet<ChunkIndices>>,
    moved_nodes: Vec<MoveResult>,
}

#[derive(Debug, Serialize)]
struct MoveResult {
    from: String,
    to: String,
}

impl<'a> From<&'a Diff> for DiffResult<'a> {
    fn from(diff: &'a Diff) -> Self {
        Self {
            new_groups: &diff.new_groups,
            new_arrays: &diff.new_arrays,
            deleted_groups: &diff.deleted_groups,
            deleted_arrays: &diff.deleted_arrays,
            updated_groups: &diff.updated_groups,
            updated_arrays: &diff.updated_arrays,
            updated_chunks: &diff.updated_chunks,
            moved_nodes: diff
                .moved_nodes
                .iter()
                .map(|m| MoveResult { from: m.from.to_string(), to: m.to.to_string() })
                .collect(),
        }
    }
}

fn paths<S: Serializer>(paths: &&BTreeSet<Path>, s: S) -> Result<S::Ok, S::Error> {
    s.collect_seq(paths.iter().map(ToString::to_string))
}

fn chunks<S: Serializer>(
    chunks: &&BTreeMap<Path, BTreeSet<ChunkIndices>>,
    s: S,
) -> Result<S::Ok, S::Error> {
    s.collect_map(chunks.iter().map(|(path, indices)| {
        (path.to_string(), indices.iter().map(|c| c.0.as_slice()).collect::<Vec<_>>())
    }))
}

/// icechunk's `ExpireResult`, with its deleted refs split into branches and tags. Every
/// list is sorted.
#[derive(Debug, Serialize)]
pub(crate) struct ExpirationResult<'a> {
    released_snapshots: Vec<String>,
    edited_snapshots: Vec<String>,
    deleted_branches: Vec<&'a str>,
    deleted_tags: Vec<&'a str>,
}

impl<'a> From<&'a ExpireResult> for ExpirationResult<'a> {
    fn from(result: &'a ExpireResult) -> Self {
        let ids = |ids: &HashSet<SnapshotId>| {
            let mut ids: Vec<_> = ids.iter().map(ToString::to_string).collect();
            ids.sort_unstable();
            ids
        };
        let mut deleted_branches = Vec::new();
        let mut deleted_tags = Vec::new();
        for deleted in &result.deleted_refs {
            match deleted {
                Ref::Branch(name) => deleted_branches.push(name.as_str()),
                Ref::Tag(name) => deleted_tags.push(name.as_str()),
            }
        }
        deleted_branches.sort_unstable();
        deleted_tags.sort_unstable();
        Self {
            released_snapshots: ids(&result.released_snapshots),
            edited_snapshots: ids(&result.edited_snapshots),
            deleted_branches,
            deleted_tags,
        }
    }
}

/// icechunk's `GCSummary` without `attributes_deleted`, which icechunk 2.2 never counts.
#[derive(Debug, Serialize)]
pub(crate) struct GcSummaryResult {
    bytes_deleted: u64,
    chunks_deleted: u64,
    manifests_deleted: u64,
    snapshots_deleted: u64,
    transaction_logs_deleted: u64,
}

impl From<&GCSummary> for GcSummaryResult {
    fn from(summary: &GCSummary) -> Self {
        Self {
            bytes_deleted: summary.bytes_deleted,
            chunks_deleted: summary.chunks_deleted,
            manifests_deleted: summary.manifests_deleted,
            snapshots_deleted: summary.snapshots_deleted,
            transaction_logs_deleted: summary.transaction_logs_deleted,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{TimeZone, Utc};
    use icechunk::format::{Move, NodeId, NodeType};

    // `ResultsContractTest` in Java reads these same strings.

    #[test]
    fn snapshot_info() {
        let id = SnapshotId::try_from("1CECHNKREP0F1RSTCMT0").unwrap();
        let snapshot = SnapshotInfo {
            id: id.clone(),
            parent_id: Some(id),
            flushed_at: Utc.with_ymd_and_hms(2026, 1, 2, 3, 4, 5).unwrap(),
            message: "m".to_owned(),
            metadata: [("k".to_owned(), serde_json::json!([1, "x"]))].into(),
            pruned_ancestor_tx_logs: Vec::new(),
        };
        assert_eq!(
            serde_json::to_string(&SnapshotInfoResult::from(&snapshot)).unwrap(),
            r#"{"id":"1CECHNKREP0F1RSTCMT0","parent_id":"1CECHNKREP0F1RSTCMT0","flushed_at":"2026-01-02T03:04:05.000000Z","message":"m","metadata":{"k":[1,"x"]}}"#
        );
    }

    #[test]
    fn diff() {
        let path = |p: &str| Path::try_from(p).unwrap();
        let set = |p: &str| BTreeSet::from([path(p)]);
        let diff = Diff {
            new_groups: set("/ng"),
            new_arrays: set("/na"),
            deleted_groups: set("/dg"),
            deleted_arrays: set("/da"),
            updated_groups: set("/ug"),
            updated_arrays: set("/ua"),
            updated_chunks: BTreeMap::from([(
                path("/ua"),
                BTreeSet::from([ChunkIndices(vec![0, 1]), ChunkIndices(vec![2, 3])]),
            )]),
            moved_nodes: vec![Move {
                from: path("/a"),
                to: path("/b"),
                node_id: NodeId::random(),
                node_type: NodeType::Group,
            }],
        };
        assert_eq!(
            serde_json::to_string(&DiffResult::from(&diff)).unwrap(),
            r#"{"new_groups":["/ng"],"new_arrays":["/na"],"deleted_groups":["/dg"],"deleted_arrays":["/da"],"updated_groups":["/ug"],"updated_arrays":["/ua"],"updated_chunks":{"/ua":[[0,1],[2,3]]},"moved_nodes":[{"from":"/a","to":"/b"}]}"#
        );
    }

    #[test]
    fn expiration() {
        let id = |s: &str| SnapshotId::try_from(s).unwrap();
        let result = ExpireResult {
            released_snapshots: HashSet::from([
                id("1CECHNKREP0F1RSTCMT0"),
                id("0CECHNKREP0F1RSTCMT0"),
            ]),
            edited_snapshots: HashSet::from([id("2CECHNKREP0F1RSTCMT0")]),
            deleted_refs: HashSet::from([
                Ref::Branch("b".to_owned()),
                Ref::Branch("a".to_owned()),
                Ref::Tag("t".to_owned()),
            ]),
        };
        assert_eq!(
            serde_json::to_string(&ExpirationResult::from(&result)).unwrap(),
            r#"{"released_snapshots":["0CECHNKREP0F1RSTCMT0","1CECHNKREP0F1RSTCMT0"],"edited_snapshots":["2CECHNKREP0F1RSTCMT0"],"deleted_branches":["a","b"],"deleted_tags":["t"]}"#
        );
    }

    #[test]
    fn gc_summary() {
        let summary = GCSummary {
            bytes_deleted: 1 << 40,
            chunks_deleted: 1,
            manifests_deleted: 2,
            snapshots_deleted: 3,
            attributes_deleted: 4,
            transaction_logs_deleted: 5,
        };
        assert_eq!(
            serde_json::to_string(&GcSummaryResult::from(&summary)).unwrap(),
            r#"{"bytes_deleted":1099511627776,"chunks_deleted":1,"manifests_deleted":2,"snapshots_deleted":3,"transaction_logs_deleted":5}"#
        );
    }
}
