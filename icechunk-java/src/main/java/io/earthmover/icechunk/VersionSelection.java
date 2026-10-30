package io.earthmover.icechunk;

/** Which side wins when a rebase finds the same chunk written by both this session and an earlier commit. */
public enum VersionSelection {
    /** Treat the conflict as unresolvable. */
    FAIL("fail"),
    /** Keep this session's chunk. */
    USE_OURS("use_ours"),
    /** Keep the chunk from the commit being rebased onto. */
    USE_THEIRS("use_theirs");

    private final String json;

    VersionSelection(String json) {
        this.json = json;
    }

    String toJson() {
        return json;
    }
}
