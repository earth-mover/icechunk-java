package io.earthmover.icechunk;

import java.nio.ByteBuffer;

/**
 * The native methods implemented in the Rust {@code icechunk-jni} crate.
 *
 * <p>Each method runs the operation on the calling thread and returns its result, or throws. Constants here must match
 * their counterparts in the Rust source.
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

    static native long storageOpen(String specJson);

    static native long repositoryOpen(long storage, int mode, String optionsJson);

    static native boolean repositoryExists(long storage);

    static native String repositoryConfig(long repository);

    static native String[] repositoryListBranches(long repository);

    static native String[] repositoryListTags(long repository);

    static native String repositoryLookupBranch(long repository, String name);

    static native String repositoryLookupTag(long repository, String name);

    static native void repositoryCreateBranch(long repository, String name, String snapshot);

    static native void repositoryDeleteBranch(long repository, String name);

    static native void repositoryResetBranch(long repository, String name, String snapshot);

    static native void repositoryCreateTag(long repository, String name, String snapshot);

    static native void repositoryDeleteTag(long repository, String name);

    static native String[] repositoryAncestry(long repository, int kind, String value);

    static native long repositoryReadonlySession(long repository, int kind, String value);

    static native long repositoryWritableSession(long repository, String branch);

    static native String sessionSnapshotId(long session);

    static native String sessionBranch(long session);

    static native boolean sessionReadOnly(long session);

    static native boolean sessionHasUncommittedChanges(long session);

    static native String sessionCommit(long session, String message);

    static native void sessionDiscardChanges(long session);

    static native long sessionStore(long session);

    static native byte[] storeGet(long store, String key, long rangeKind, long a, long b);

    /** Fills {@code out} with the buffer's owner and the bytes outstanding, see {@link NativeBuffers}. */
    static native ByteBuffer storeGetBuffer(long store, String key, long rangeKind, long a, long b, long[] out);

    static native byte[][] storeGetMany(long store, String[] keys, long[] ranges);

    /** Fills {@code out} with one owner per key, then the bytes outstanding. */
    static native ByteBuffer[] storeGetManyBuffers(long store, String[] keys, long[] ranges, long[] out);

    static native void storeSet(long store, String key, byte[] value, int offset, int length, boolean onlyIfNew);

    static native void storeSetBuffer(
            long store, String key, ByteBuffer direct, int position, int length, boolean onlyIfNew);

    static native boolean storeExists(long store, String key);

    static native long storeSize(long store, String key);

    static native void storeDelete(long store, String key);

    static native void storeDeleteDir(long store, String prefix);

    static native boolean storeIsEmpty(long store, String prefix);

    static native String[] storeList(long store, int mode, String prefix);

    static native boolean storeReadOnly(long store);

    static native void bufferRelease(long owner);

    static native long bufferOutstanding();
}
