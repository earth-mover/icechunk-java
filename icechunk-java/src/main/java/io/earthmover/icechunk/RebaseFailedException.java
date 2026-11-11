package io.earthmover.icechunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A rebase found conflicts its {@link ConflictSolver} could not resolve. Thrown by {@link Session#rebase}, and by a
 * commit that rebases ({@link CommitOptions.Builder#rebaseWith}).
 *
 * <p>The session keeps its changes, rebased onto every commit before {@link #snapshotId()}. Fix the conflicting
 * changes, or discard them, and rebase again.
 */
public final class RebaseFailedException extends ConflictException {
    private static final long serialVersionUID = 1L;

    private final String snapshotId;
    private final transient List<Conflict> conflicts;

    private RebaseFailedException(String message, String snapshotId, List<Conflict> conflicts) {
        super(message);
        this.snapshotId = snapshotId;
        this.conflicts = conflicts;
    }

    /** Read a {@code RebaseFailedResult} document from the native layer. */
    static RebaseFailedException fromJson(String json) {
        Map<String, Object> fields = JsonReader.readObject(json);
        List<Conflict> conflicts = new ArrayList<>();
        for (Object conflict : JsonReader.array(fields, "conflicts")) {
            conflicts.add(Conflict.fromJson(JsonReader.asObject(conflict, "conflict")));
        }
        String message = JsonReader.string(fields, "message");
        if (!conflicts.isEmpty()) {
            message = message + ": " + conflicts;
        }
        return new RebaseFailedException(
                message, JsonReader.string(fields, "snapshot"), Collections.unmodifiableList(conflicts));
    }

    /** The commit the session could not be rebased onto. */
    public SnapshotId snapshotId() {
        return SnapshotId.of(snapshotId);
    }

    /** The conflicts the solver could not resolve. */
    public List<Conflict> conflicts() {
        return conflicts == null ? Collections.emptyList() : conflicts;
    }
}
