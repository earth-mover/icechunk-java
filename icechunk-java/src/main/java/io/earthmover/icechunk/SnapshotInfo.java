package io.earthmover.icechunk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One entry in a repository's history, as returned by {@link Repository#ancestry}. */
public final class SnapshotInfo {
    private final SnapshotId id;
    private final SnapshotId parentId;
    private final Instant writtenAt;
    private final String message;
    private final Map<String, Object> metadata;

    private SnapshotInfo(
            SnapshotId id, SnapshotId parentId, Instant writtenAt, String message, Map<String, Object> metadata) {
        this.id = id;
        this.parentId = parentId;
        this.writtenAt = writtenAt;
        this.message = message;
        this.metadata = metadata;
    }

    /** Read a {@code SnapshotInfoResult} document from the native layer. */
    static SnapshotInfo fromJson(Map<String, Object> fields) {
        String parent = JsonReader.optionalString(fields, "parent_id");
        return new SnapshotInfo(
                SnapshotId.of(JsonReader.string(fields, "id")),
                parent == null ? null : SnapshotId.of(parent),
                Instant.parse(JsonReader.string(fields, "flushed_at")),
                JsonReader.string(fields, "message"),
                JsonReader.object(fields, "metadata"));
    }

    static List<SnapshotInfo> listFromJson(String json) {
        List<Map<String, Object>> documents = JsonReader.readObjects(json);
        List<SnapshotInfo> snapshots = new ArrayList<>(documents.size());
        for (Map<String, Object> document : documents) {
            snapshots.add(fromJson(document));
        }
        return Collections.unmodifiableList(snapshots);
    }

    public SnapshotId id() {
        return id;
    }

    /** The previous snapshot; empty for the repository's first snapshot. */
    public Optional<SnapshotId> parentId() {
        return Optional.ofNullable(parentId);
    }

    /** When the snapshot was committed. */
    public Instant writtenAt() {
        return writtenAt;
    }

    public String message() {
        return message;
    }

    /**
     * The metadata stored with the commit, as JSON values: {@code String}, {@code Boolean}, {@code Long} (or
     * {@code BigInteger}), {@code Double}, {@code List}, {@code Map<String, Object>} or null. The map and everything
     * in it are unmodifiable.
     */
    public Map<String, Object> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SnapshotInfo)) {
            return false;
        }
        SnapshotInfo that = (SnapshotInfo) other;
        return id.equals(that.id)
                && Objects.equals(parentId, that.parentId)
                && writtenAt.equals(that.writtenAt)
                && message.equals(that.message)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id + " " + writtenAt + " " + message;
    }
}
