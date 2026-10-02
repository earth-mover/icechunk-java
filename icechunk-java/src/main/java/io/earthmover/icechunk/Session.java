package io.earthmover.icechunk;

import java.util.Optional;

/**
 * A view of one repository version, and for a writable session, the uncommitted changes made on top of it.
 *
 * <p>Read through {@link #store()}. A writable session collects every write in memory and on storage until
 * {@link #commit} makes them a new snapshot, or {@link #discardChanges} drops them.
 *
 * <p>A session is safe to use from several threads. Reads and writes run concurrently; {@link #commit} and
 * {@link #discardChanges} wait for running reads and writes, and block new ones until they finish. Closing a session
 * closes its store.
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
        String json = options.toJson();
        try {
            return SnapshotId.of(Native.sessionCommit(handle(), message, json));
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

    /** Drop all uncommitted changes. */
    public void discardChanges() {
        try {
            Native.sessionDiscardChanges(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Close the store, then the session, under the lock {@link #store()} takes, so no new store can appear between. */
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
