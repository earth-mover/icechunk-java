package io.earthmover.icechunk;

import java.time.Instant;
import java.util.Objects;

/**
 * Selects a version of a repository: the tip of a branch, a tag, a specific snapshot, or a branch as it was at a point
 * in time.
 */
public final class Version {
    private static final Instant FIRST_INSTANT = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant LAST_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private final String json;
    private final String description;

    private Version(Json json, String description) {
        this.json = json.toString();
        this.description = description;
    }

    /** The latest commit on {@code branch}, resolved when the version is used. */
    public static Version branch(String branch) {
        Objects.requireNonNull(branch, "branch");
        return new Version(Json.object().put("type", "branch").put("name", branch), "branch " + branch);
    }

    /** The snapshot {@code tag} points to. */
    public static Version tag(String tag) {
        Objects.requireNonNull(tag, "tag");
        return new Version(Json.object().put("type", "tag").put("name", tag), "tag " + tag);
    }

    /** A specific snapshot. */
    public static Version snapshot(SnapshotId id) {
        Objects.requireNonNull(id, "id");
        return new Version(Json.object().put("type", "snapshot_id").put("id", id.toString()), "snapshot " + id);
    }

    /**
     * The newest snapshot in {@code branch}'s history committed at or before {@code at}. A time names no single
     * snapshot on its own, since each branch has its own history, so the branch is required.
     *
     * <p>Using the version throws {@link IcechunkException} if the branch has no snapshot that old.
     *
     * @throws IllegalArgumentException if {@code at} is outside the years 0000 to 9999, which RFC 3339 timestamps
     *     cannot express
     */
    public static Version asOf(String branch, Instant at) {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(at, "at");
        if (at.isBefore(FIRST_INSTANT) || at.isAfter(LAST_INSTANT)) {
            throw new IllegalArgumentException("asOf time out of range: " + at);
        }
        return new Version(
                Json.object().put("type", "as_of").put("branch", branch).put("at", at.toString()),
                "branch " + branch + " as of " + at);
    }

    String toJson() {
        return json;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version && json.equals(((Version) other).json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return description;
    }
}
