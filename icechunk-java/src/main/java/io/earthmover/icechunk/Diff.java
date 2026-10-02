package io.earthmover.icechunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The changes between two versions of a repository, from {@link Repository#diff}, or the uncommitted changes in a
 * session, from {@link Session#status}.
 *
 * <p>Paths are absolute node paths such as {@code /group/array}, ordered component by component, so {@code /a/b}
 * comes before {@code /a-b}. Chunks are listed by their chunk grid indices, one {@code List<Long>} per chunk.
 */
public final class Diff {
    private final Set<String> newGroups;
    private final Set<String> newArrays;
    private final Set<String> deletedGroups;
    private final Set<String> deletedArrays;
    private final Set<String> updatedGroups;
    private final Set<String> updatedArrays;
    private final Map<String, List<List<Long>>> updatedChunks;
    private final List<Move> movedNodes;

    /** A group or array moved from one path to another. */
    public static final class Move {
        private final String from;
        private final String to;

        private Move(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String from() {
            return from;
        }

        public String to() {
            return to;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Move && from.equals(((Move) other).from) && to.equals(((Move) other).to);
        }

        @Override
        public int hashCode() {
            return 31 * from.hashCode() + to.hashCode();
        }

        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    @SuppressWarnings("unchecked") // the native layer writes chunk indices as lists of integers
    private Diff(Map<String, Object> fields) {
        newGroups = JsonReader.stringSet(fields, "new_groups");
        newArrays = JsonReader.stringSet(fields, "new_arrays");
        deletedGroups = JsonReader.stringSet(fields, "deleted_groups");
        deletedArrays = JsonReader.stringSet(fields, "deleted_arrays");
        updatedGroups = JsonReader.stringSet(fields, "updated_groups");
        updatedArrays = JsonReader.stringSet(fields, "updated_arrays");
        updatedChunks = (Map<String, List<List<Long>>>) (Object) JsonReader.object(fields, "updated_chunks");
        List<Move> moves = new ArrayList<>();
        for (Object move : JsonReader.array(fields, "moved_nodes")) {
            Map<String, Object> m = JsonReader.asObject(move, "moved_nodes");
            moves.add(new Move(JsonReader.string(m, "from"), JsonReader.string(m, "to")));
        }
        movedNodes = Collections.unmodifiableList(moves);
    }

    /** Read a {@code DiffResult} document from the native layer. */
    static Diff fromJson(String json) {
        return new Diff(JsonReader.readObject(json));
    }

    public Set<String> newGroups() {
        return newGroups;
    }

    public Set<String> newArrays() {
        return newArrays;
    }

    public Set<String> deletedGroups() {
        return deletedGroups;
    }

    public Set<String> deletedArrays() {
        return deletedArrays;
    }

    /** Groups whose metadata changed. */
    public Set<String> updatedGroups() {
        return updatedGroups;
    }

    /** Arrays whose metadata changed. */
    public Set<String> updatedArrays() {
        return updatedArrays;
    }

    /** For each array with written or deleted chunks, the indices of those chunks. */
    public Map<String, List<List<Long>>> updatedChunks() {
        return updatedChunks;
    }

    public List<Move> movedNodes() {
        return movedNodes;
    }

    /** Returns true if there are no changes. */
    public boolean isEmpty() {
        return newGroups.isEmpty()
                && newArrays.isEmpty()
                && deletedGroups.isEmpty()
                && deletedArrays.isEmpty()
                && updatedGroups.isEmpty()
                && updatedArrays.isEmpty()
                && updatedChunks.isEmpty()
                && movedNodes.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Diff)) {
            return false;
        }
        Diff that = (Diff) other;
        return newGroups.equals(that.newGroups)
                && newArrays.equals(that.newArrays)
                && deletedGroups.equals(that.deletedGroups)
                && deletedArrays.equals(that.deletedArrays)
                && updatedGroups.equals(that.updatedGroups)
                && updatedArrays.equals(that.updatedArrays)
                && updatedChunks.equals(that.updatedChunks)
                && movedNodes.equals(that.movedNodes);
    }

    @Override
    public int hashCode() {
        return newArrays.hashCode() ^ updatedChunks.hashCode();
    }

    @Override
    public String toString() {
        return "Diff{newGroups=" + newGroups + ", newArrays=" + newArrays + ", deletedGroups=" + deletedGroups
                + ", deletedArrays=" + deletedArrays + ", updatedGroups=" + updatedGroups + ", updatedArrays="
                + updatedArrays + ", updatedChunks=" + updatedChunks + ", movedNodes=" + movedNodes + "}";
    }
}
