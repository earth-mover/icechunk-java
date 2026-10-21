package io.earthmover.icechunk;

/**
 * Another writer changed the repository while an operation required it not to.
 *
 * <p>These operations throw it:
 *
 * <ul>
 *   <li>{@link Session#commit}, when another writer committed to the same branch since the session started. Open a
 *       new writable session and redo the changes.
 *   <li>{@link Repository#resetBranch(String, SnapshotId, SnapshotId)}, when the branch no longer points to the
 *       expected snapshot.
 *   <li>Operations that update the repository's list of branches, tags and snapshots, such as creating a branch or
 *       {@link Repository#garbageCollect}, when other writers kept updating that list until icechunk ran out of
 *       retries.
 * </ul>
 */
public class ConflictException extends IcechunkException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
