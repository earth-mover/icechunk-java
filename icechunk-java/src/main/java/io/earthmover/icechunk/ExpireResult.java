package io.earthmover.icechunk;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** What {@link Repository#expireSnapshots} changed. Every set is sorted and unmodifiable. */
public final class ExpireResult {
    private final Set<SnapshotId> releasedSnapshots;
    private final Set<SnapshotId> editedSnapshots;
    private final Set<String> deletedBranches;
    private final Set<String> deletedTags;

    private ExpireResult(Map<String, Object> fields) {
        releasedSnapshots = snapshotIds(fields, "released_snapshots");
        editedSnapshots = snapshotIds(fields, "edited_snapshots");
        deletedBranches = JsonReader.stringSet(fields, "deleted_branches");
        deletedTags = JsonReader.stringSet(fields, "deleted_tags");
    }

    /** Read an {@code ExpirationResult} document from the native layer. */
    static ExpireResult fromJson(String json) {
        return new ExpireResult(JsonReader.readObject(json));
    }

    private static Set<SnapshotId> snapshotIds(Map<String, Object> fields, String name) {
        Set<SnapshotId> ids = new LinkedHashSet<>();
        for (String id : JsonReader.stringSet(fields, name)) {
            ids.add(SnapshotId.of(id));
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Snapshots expiration took out of history. On spec version 2 repositories no branch or tag leads to them
     * afterwards. On spec version 1 repositories another branch or tag may still lead to one, and a tip older than the
     * cutoff is both released and edited: it stays in its history, with the first snapshot as parent.
     */
    public Set<SnapshotId> releasedSnapshots() {
        return releasedSnapshots;
    }

    /** Snapshots kept whose parent was released. Each now has the first snapshot of its history as parent. */
    public Set<SnapshotId> editedSnapshots() {
        return editedSnapshots;
    }

    public Set<String> deletedBranches() {
        return deletedBranches;
    }

    public Set<String> deletedTags() {
        return deletedTags;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExpireResult)) {
            return false;
        }
        ExpireResult that = (ExpireResult) other;
        return releasedSnapshots.equals(that.releasedSnapshots)
                && editedSnapshots.equals(that.editedSnapshots)
                && deletedBranches.equals(that.deletedBranches)
                && deletedTags.equals(that.deletedTags);
    }

    @Override
    public int hashCode() {
        return releasedSnapshots.hashCode() ^ editedSnapshots.hashCode();
    }

    @Override
    public String toString() {
        return "ExpireResult{releasedSnapshots=" + releasedSnapshots + ", editedSnapshots=" + editedSnapshots
                + ", deletedBranches=" + deletedBranches + ", deletedTags=" + deletedTags + "}";
    }
}
