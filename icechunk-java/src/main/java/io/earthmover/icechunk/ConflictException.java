package io.earthmover.icechunk;

/**
 * A branch moved while an operation required it not to.
 *
 * <p>{@link Session#commit} throws it when another writer committed to the same branch since the session started;
 * open a new writable session and redo the changes. {@link Repository#resetBranch(String, SnapshotId, SnapshotId)}
 * throws it when the branch no longer points to the expected snapshot.
 */
public class ConflictException extends IcechunkException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
