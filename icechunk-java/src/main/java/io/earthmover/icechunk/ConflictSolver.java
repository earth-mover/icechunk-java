package io.earthmover.icechunk;

import java.util.Objects;

/**
 * How {@link Session#rebase} and a rebasing commit ({@link CommitOptions.Builder#rebaseWith}) handle changes that
 * conflict with commits made since the session started.
 *
 * <pre>{@code
 * session.rebase(ConflictSolver.detector());                          // fail on any conflict
 * session.rebase(ConflictSolver.basic());                             // keep our chunks
 * session.rebase(ConflictSolver.basic(VersionSelection.USE_THEIRS, false, false));
 * }</pre>
 */
public final class ConflictSolver {
    private final String json;

    private ConflictSolver(String json) {
        this.json = json;
    }

    /** Resolve nothing: any conflict makes the rebase throw {@link RebaseFailedException}. */
    public static ConflictSolver detector() {
        return new ConflictSolver(Json.object().put("type", "detector").toString());
    }

    /** {@link #basic(VersionSelection, boolean, boolean)} keeping this session's chunks and allowing deletes. */
    public static ConflictSolver basic() {
        return basic(VersionSelection.USE_OURS, false, false);
    }

    /**
     * Resolve chunk conflicts with {@code onChunkConflict}; also accept deleting an array or group that an earlier
     * commit updated, unless the corresponding {@code failOn...} flag is set. Other conflicts, such as both sides
     * changing an array's metadata, still fail.
     */
    public static ConflictSolver basic(
            VersionSelection onChunkConflict, boolean failOnDeleteOfUpdatedArray, boolean failOnDeleteOfUpdatedGroup) {
        return new ConflictSolver(Json.object()
                .put("type", "basic")
                .put(
                        "on_chunk_conflict",
                        Objects.requireNonNull(onChunkConflict, "onChunkConflict")
                                .toJson())
                .put("fail_on_delete_of_updated_array", failOnDeleteOfUpdatedArray)
                .put("fail_on_delete_of_updated_group", failOnDeleteOfUpdatedGroup)
                .toString());
    }

    String toJson() {
        return json;
    }

    @Override
    public String toString() {
        return "ConflictSolver" + json;
    }
}
