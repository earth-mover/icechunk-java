package io.earthmover.icechunk;

import java.util.Objects;

/** The id of an icechunk snapshot, the immutable state of a repository after a commit. */
public final class SnapshotId {
    private final String id;

    private SnapshotId(String id) {
        this.id = id;
    }

    /**
     * Wrap a snapshot id string, as printed by icechunk. The format is checked when the id is used, not here.
     *
     * @throws NullPointerException if {@code id} is null
     */
    public static SnapshotId of(String id) {
        return new SnapshotId(Objects.requireNonNull(id, "id"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SnapshotId && ((SnapshotId) other).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Returns the id string. */
    @Override
    public String toString() {
        return id;
    }
}
