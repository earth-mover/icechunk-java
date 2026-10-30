package io.earthmover.icechunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A change in a session that a rebase could not reconcile with an earlier commit; see {@link RebaseFailedException}. */
public final class Conflict {
    /** The kinds of conflict icechunk detects. */
    public enum Type {
        /** This session added a node at a path where an earlier commit also added one. */
        NEW_NODE_CONFLICTS_WITH_EXISTING_NODE,
        /** This session added a node inside a group an earlier commit deleted or replaced. */
        NEW_NODE_IN_INVALID_GROUP,
        /** Both changed the same node's Zarr metadata. */
        ZARR_METADATA_DOUBLE_UPDATE,
        /** This session changed the metadata of an array an earlier commit deleted. */
        ZARR_METADATA_UPDATE_OF_DELETED_ARRAY,
        /** This session changed the metadata of a group an earlier commit deleted. */
        ZARR_METADATA_UPDATE_OF_DELETED_GROUP,
        /** Both wrote the same chunks; {@link #chunks()} lists them. */
        CHUNK_DOUBLE_UPDATE,
        /** This session wrote chunks of an array an earlier commit deleted. */
        CHUNKS_UPDATED_IN_DELETED_ARRAY,
        /** This session wrote chunks of an array whose metadata an earlier commit changed. */
        CHUNKS_UPDATED_IN_UPDATED_ARRAY,
        /** This session deleted an array an earlier commit changed. */
        DELETE_OF_UPDATED_ARRAY,
        /** This session deleted a group an earlier commit changed. */
        DELETE_OF_UPDATED_GROUP,
        /** Sessions that move nodes cannot be rebased. */
        MOVE_OPERATION_CANNOT_BE_REBASED,
        /** A kind this version of the binding does not know. */
        UNKNOWN
    }

    private final Type type;
    private final String path;
    private final List<List<Long>> chunks;

    private Conflict(Type type, String path, List<List<Long>> chunks) {
        this.type = type;
        this.path = path;
        this.chunks = chunks;
    }

    @SuppressWarnings("unchecked") // the native layer writes chunk indices as lists of integers
    static Conflict fromJson(Map<String, Object> fields) {
        String name = JsonReader.string(fields, "type");
        Type type;
        try {
            type = Type.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = Type.UNKNOWN;
        }
        List<List<Long>> chunks = Collections.emptyList();
        if (fields.get("chunks") != null) {
            List<List<Long>> indices = new ArrayList<>();
            for (Object chunk : JsonReader.array(fields, "chunks")) {
                indices.add(Collections.unmodifiableList((List<Long>) chunk));
            }
            chunks = Collections.unmodifiableList(indices);
        }
        return new Conflict(type, JsonReader.optionalString(fields, "path"), chunks);
    }

    public Type type() {
        return type;
    }

    /** The node path, such as {@code /group/array}; empty for {@link Type#MOVE_OPERATION_CANNOT_BE_REBASED}. */
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /** For {@link Type#CHUNK_DOUBLE_UPDATE}, the indices of the chunks both sides wrote; otherwise empty. */
    public List<List<Long>> chunks() {
        return chunks;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Conflict)) {
            return false;
        }
        Conflict that = (Conflict) other;
        return type == that.type && Objects.equals(path, that.path) && chunks.equals(that.chunks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, path, chunks);
    }

    @Override
    public String toString() {
        return type + (path == null ? "" : " " + path) + (chunks.isEmpty() ? "" : " " + chunks);
    }
}
