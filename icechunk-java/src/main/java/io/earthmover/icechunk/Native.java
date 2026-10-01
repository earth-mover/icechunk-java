package io.earthmover.icechunk;

/**
 * The native methods implemented in the Rust {@code icechunk-jni} crate.
 *
 * <p>Every method except {@link #close} and {@link #cancel} starts an operation on the native runtime and returns at
 * once with a task id. The result arrives through the {@link NativeCall} passed as the first argument. Constants here
 * must match their counterparts in the Rust source.
 */
final class Native {
    static final int OPEN = 0;
    static final int CREATE = 1;
    static final int OPEN_OR_CREATE = 2;

    static final int VERSION_BRANCH = 0;
    static final int VERSION_TAG = 1;
    static final int VERSION_SNAPSHOT = 2;

    static final long RANGE_ALL = 0;
    static final long RANGE_BOUNDED = 1;
    static final long RANGE_FROM = 2;
    static final long RANGE_SUFFIX = 3;

    static final int LIST_ALL = 0;
    static final int LIST_PREFIX = 1;
    static final int LIST_DIR = 2;

    static {
        NativeLoader.load();
    }

    private Native() {}

    static native void close(long handle);

    static native void cancel(long task);

    static native long storageOpen(NativeCall call, String specJson);

    static native long repositoryOpen(NativeCall call, long storage, int mode, String optionsJson);

    static native long repositoryExists(NativeCall call, long storage);

    static native long repositoryConfig(NativeCall call, long repository);

    static native long repositoryListBranches(NativeCall call, long repository);

    static native long repositoryListTags(NativeCall call, long repository);

    static native long repositoryLookupBranch(NativeCall call, long repository, String name);

    static native long repositoryLookupTag(NativeCall call, long repository, String name);

    static native long repositoryCreateBranch(NativeCall call, long repository, String name, String snapshot);

    static native long repositoryDeleteBranch(NativeCall call, long repository, String name);

    static native long repositoryResetBranch(NativeCall call, long repository, String name, String snapshot);

    static native long repositoryCreateTag(NativeCall call, long repository, String name, String snapshot);

    static native long repositoryDeleteTag(NativeCall call, long repository, String name);

    static native long repositoryAncestry(NativeCall call, long repository, int kind, String value);

    static native long repositoryReadonlySession(NativeCall call, long repository, int kind, String value);

    static native long repositoryWritableSession(NativeCall call, long repository, String branch);

    static native long sessionSnapshotId(NativeCall call, long session);

    static native long sessionBranch(NativeCall call, long session);

    static native long sessionReadOnly(NativeCall call, long session);

    static native long sessionHasUncommittedChanges(NativeCall call, long session);

    static native long sessionCommit(NativeCall call, long session, String message);

    static native long sessionDiscardChanges(NativeCall call, long session);

    static native long sessionStore(NativeCall call, long session, int concurrency);

    static native long storeGet(NativeCall call, long store, String key, long rangeKind, long a, long b);

    static native long storeGetMany(NativeCall call, long store, String[] keys, long[] ranges);

    static native long storeSet(NativeCall call, long store, String key, byte[] value);

    static native long storeSetIfNotExists(NativeCall call, long store, String key, byte[] value);

    static native long storeExists(NativeCall call, long store, String key);

    static native long storeSize(NativeCall call, long store, String key);

    static native long storeDelete(NativeCall call, long store, String key);

    static native long storeDeleteDir(NativeCall call, long store, String prefix);

    static native long storeIsEmpty(NativeCall call, long store, String prefix);

    static native long storeList(NativeCall call, long store, int mode, String prefix);

    static native long storeReadOnly(NativeCall call, long store);
}
