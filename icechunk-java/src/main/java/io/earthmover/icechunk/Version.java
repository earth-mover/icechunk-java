package io.earthmover.icechunk;

import java.util.Objects;

/** Selects the version of a repository to read: the tip of a branch, a tag, or a specific snapshot. */
public final class Version {
    private final int kind;
    private final String value;

    private Version(int kind, String value) {
        this.kind = kind;
        this.value = Objects.requireNonNull(value);
    }

    /** The latest commit on {@code branch}, resolved when the session opens. */
    public static Version branch(String branch) {
        return new Version(Native.VERSION_BRANCH, branch);
    }

    /** The snapshot {@code tag} points to. */
    public static Version tag(String tag) {
        return new Version(Native.VERSION_TAG, tag);
    }

    /** A specific snapshot. */
    public static Version snapshot(SnapshotId id) {
        return new Version(Native.VERSION_SNAPSHOT, id.toString());
    }

    int kind() {
        return kind;
    }

    String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Version)) {
            return false;
        }
        Version that = (Version) other;
        return kind == that.kind && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return 31 * kind + value.hashCode();
    }

    @Override
    public String toString() {
        switch (kind) {
            case Native.VERSION_BRANCH:
                return "branch " + value;
            case Native.VERSION_TAG:
                return "tag " + value;
            default:
                return "snapshot " + value;
        }
    }
}
