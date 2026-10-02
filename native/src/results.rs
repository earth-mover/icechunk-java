//! The JSON documents native methods return to Java for structured results.
//!
//! Like the documents in `spec`, these are a wire contract with the Java classes that
//! read them (`SnapshotInfo`, `Diff`), not icechunk's own serde formats. Each type
//! borrows from icechunk's result and is written straight to the output, so a long
//! history or a large diff is not copied into an intermediate tree.

use std::collections::{BTreeMap, BTreeSet};

use chrono::SecondsFormat;
use icechunk::diff::Diff;
use icechunk::format::snapshot::{SnapshotInfo, SnapshotProperties};
use icechunk::format::{ChunkIndices, Path};
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

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{TimeZone, Utc};
    use icechunk::format::{Move, NodeId, NodeType, SnapshotId};

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
}
