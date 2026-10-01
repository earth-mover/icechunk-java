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
    private static final int DEFAULT_CONCURRENCY = 10;

    private final Object storeLock = new Object();
    private Store store;

    Session(long handle) {
        super(handle);
    }

    /** The snapshot this session started from, or after a commit, the snapshot it created. */
    public SnapshotId snapshotId() {
        long h = handle();
        return SnapshotId.of(NativeCall.runString(call -> Native.sessionSnapshotId(call, h)));
    }

    /** The branch a writable or branch-based session tracks; empty for a tag or snapshot. */
    public Optional<String> branch() {
        long h = handle();
        return Optional.ofNullable(NativeCall.runString(call -> Native.sessionBranch(call, h)));
    }

    public boolean isReadOnly() {
        long h = handle();
        return NativeCall.runBoolean(call -> Native.sessionReadOnly(call, h));
    }

    public boolean hasUncommittedChanges() {
        long h = handle();
        return NativeCall.runBoolean(call -> Native.sessionHasUncommittedChanges(call, h));
    }

    /**
     * The Zarr store for this session. The same store is returned on every call, and it stays valid after a commit.
     */
    public Store store() {
        synchronized (storeLock) {
            if (store == null || store.isClosed()) {
                long h = handle();
                store = new Store(NativeCall.runLong(call -> Native.sessionStore(call, h, DEFAULT_CONCURRENCY)));
            }
            return store;
        }
    }

    /**
     * Commit the uncommitted changes as a new snapshot on this session's branch.
     *
     * @return the new snapshot
     * @throws ConflictException if another writer committed to the branch since this session started
     * @throws IcechunkException if the session is read-only or has no changes
     */
    public SnapshotId commit(String message) {
        long h = handle();
        return SnapshotId.of(NativeCall.runString(call -> Native.sessionCommit(call, h, message)));
    }

    /** Drop all uncommitted changes. */
    public void discardChanges() {
        long h = handle();
        NativeCall.runVoid(call -> Native.sessionDiscardChanges(call, h));
    }

    @Override
    public void close() {
        synchronized (storeLock) {
            if (store != null) {
                store.close();
            }
        }
        super.close();
    }
}
