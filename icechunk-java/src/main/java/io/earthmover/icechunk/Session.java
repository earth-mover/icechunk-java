package io.earthmover.icechunk;

import java.util.Optional;

/**
 * A view of one repository version, and for a writable session, the uncommitted changes made on top of it.
 *
 * <p>Read and write through {@link #store()}. A writable session collects every write in memory and on storage until
 * {@link #commit} makes them a new snapshot, or {@link #discardChanges} drops them.
 *
 * <p>A session is safe to use from several threads. Reads and writes run concurrently. {@link #commit} and
 * {@link #discardChanges} wait for running reads and writes to finish, and new reads and writes wait until the commit
 * or discard is done. Closing a session closes its store.
 */
public final class Session extends NativeHandle {
    private final Object storeLock = new Object();
    // Volatile so the common case, an open store, is returned without taking the lock.
    private volatile Store store;

    Session(long handle) {
        super(handle);
    }

    /** The snapshot this session started from, or after a commit, the snapshot it created. */
    public SnapshotId snapshotId() {
        try {
            return SnapshotId.of(Native.sessionSnapshotId(handle()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The branch a writable session commits to; empty for a read-only session, and after a commit. */
    public Optional<String> branch() {
        try {
            return Optional.ofNullable(Native.sessionBranch(handle()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Returns true for a session opened read-only, and for a writable session after it commits. */
    public boolean isReadOnly() {
        try {
            return Native.sessionReadOnly(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Returns true if the session has writes that are neither committed nor discarded. */
    public boolean hasUncommittedChanges() {
        try {
            return Native.sessionHasUncommittedChanges(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The Zarr store for this session. The same store is returned on every call. */
    public Store store() {
        Store current = store;
        if (current != null && !current.isClosed()) {
            return current;
        }
        synchronized (storeLock) {
            if (store == null || store.isClosed()) {
                try {
                    store = new Store(Native.sessionStore(handle()));
                } finally {
                    HandleCleaner.reachabilityFence(this);
                }
            }
            return store;
        }
    }

    /**
     * Commit the uncommitted changes as a new snapshot on this session's branch.
     *
     * <p>After a successful commit the session is read-only; open a new writable session for further changes.
     *
     * @return the new snapshot
     * @throws ConflictException if another writer committed to the branch since this session started
     * @throws IcechunkException if the session is read-only or has no changes
     */
    public SnapshotId commit(String message) {
        return commit(message, CommitOptions.defaults());
    }

    /**
     * Commit the uncommitted changes as a new snapshot, with metadata or other {@code options}.
     *
     * @see #commit(String)
     */
    public SnapshotId commit(String message, CommitOptions options) {
        return commit(message, options, Native.COMMIT);
    }

    /**
     * Replace the snapshot at the tip of the branch with one holding its changes and this session's, with {@code
     * message}. The replaced snapshot leaves the branch's history. The session must have started from the branch's
     * current tip.
     *
     * @return the new snapshot
     * @throws ConflictException if another writer committed to the branch since this session started
     * @throws IcechunkException if the session is read-only, has no changes, or the tip is the repository's first
     *     snapshot
     */
    public SnapshotId amend(String message) {
        return amend(message, CommitOptions.defaults());
    }

    /** {@link #amend(String)}, with metadata or other {@code options}. */
    public SnapshotId amend(String message, CommitOptions options) {
        return commit(message, options, Native.AMEND);
    }

    /**
     * Write the uncommitted changes as a snapshot without moving the branch, as icechunk-python's {@code flush} does.
     * Open a session on the returned snapshot, or point a branch or tag at it, to use it. After a flush the session is
     * read-only.
     *
     * @return the new snapshot
     */
    public SnapshotId flush(String message) {
        return flush(message, CommitOptions.defaults());
    }

    /** {@link #flush(String)}, with metadata or other {@code options}. */
    public SnapshotId flush(String message, CommitOptions options) {
        return commit(message, options, Native.FLUSH);
    }

    private SnapshotId commit(String message, CommitOptions options, int kind) {
        String json = options.toJson();
        try {
            return SnapshotId.of(Native.sessionCommit(handle(), message, json, kind));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * Move this session's uncommitted changes on top of every commit made to its branch since the session started, so
     * that a following {@link #commit} succeeds. {@code solver} decides what happens when the changes conflict.
     *
     * @throws RebaseFailedException if the solver cannot resolve a conflict; the session is then rebased onto the
     *     commits before the one that conflicts
     * @throws IcechunkException if the session is read-only
     */
    public void rebase(ConflictSolver solver) {
        String json = solver.toJson();
        try {
            Native.sessionRebase(handle(), json);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * Move or rename the group or array at {@code from}, with everything under it, to {@code to}. Paths are absolute
     * node paths such as {@code /group/array}; the parent of {@code to} must exist. Works only in a session from
     * {@link Repository#rearrangeSession}, and only in repositories with the version 2 format.
     *
     * @throws IcechunkException if the session cannot move nodes, {@code from} does not exist, {@code to} exists, or
     *     {@code to} is inside {@code from}
     * @throws IllegalArgumentException if a path is not an absolute node path
     */
    public void move(String from, String to) {
        try {
            Native.sessionMove(handle(), from, to);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The uncommitted changes. */
    public Diff status() {
        String json;
        try {
            json = Native.sessionStatus(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        return Diff.fromJson(json);
    }

    /**
     * Drop all uncommitted changes.
     *
     * @throws IcechunkException if the session is read-only
     */
    public void discardChanges() {
        try {
            Native.sessionDiscardChanges(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * Close the store, then the session. Both close under the lock {@link #store()} takes, so no new store can be
     * opened after the store closes and before the session does.
     */
    @Override
    public void close() {
        synchronized (storeLock) {
            if (store != null) {
                store.close();
            }
            super.close();
        }
    }
}
