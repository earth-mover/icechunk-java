package io.earthmover.icechunk;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One entry in a repository's history, as returned by {@link Repository#ancestry}. */
public final class SnapshotInfo {
    private final SnapshotId id;
    private final SnapshotId parentId;
    private final Instant writtenAt;
    private final String message;

    SnapshotInfo(SnapshotId id, SnapshotId parentId, Instant writtenAt, String message) {
        this.id = id;
        this.parentId = parentId;
        this.writtenAt = writtenAt;
        this.message = message;
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

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SnapshotInfo)) {
            return false;
        }
        SnapshotInfo that = (SnapshotInfo) other;
        return id.equals(that.id)
                && Objects.equals(parentId, that.parentId)
                && writtenAt.equals(that.writtenAt)
                && message.equals(that.message);
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
