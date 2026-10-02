package io.earthmover.icechunk;

import java.nio.ByteBuffer;

/**
 * The native methods implemented in the Rust {@code icechunk-jni} crate.
 *
 * <p>Each method runs the operation on the calling thread and returns its result, or throws. Constants here must match
 * their counterparts in the Rust source.
 */
final class Native {
    /**
     * The version of everything this class and the native library must agree on: the native methods' names and
     * signatures, the constants below, and the JSON documents they exchange. A library built for another version
     * would read arguments it does not understand, or crash the JVM, so loading checks it first.
     */
    static final int ABI_VERSION = 2;

    static final int OPEN = 0;
    static final int CREATE = 1;
    static final int OPEN_OR_CREATE = 2;

    static final long RANGE_BOUNDED = 1;
    static final long RANGE_FROM = 2;
    static final long RANGE_SUFFIX = 3;

    static final int LIST_ALL = 0;
    static final int LIST_PREFIX = 1;
    static final int LIST_DIR = 2;

    static {
        NativeLoader.load();
        checkAbiVersion();
    }

    private Native() {}

    private static void checkAbiVersion() {
        String found;
        try {
            int loaded = abiVersion();
            if (loaded == ABI_VERSION) {
                return;
            }
            found = "interface version " + loaded;
        } catch (UnsatisfiedLinkError e) {
            found = "no interface version";
        }
        throw new UnsatisfiedLinkError("the icechunk native library has " + found + " but this jar needs version "
                + ABI_VERSION + ", so it was built from other sources; look for an old copy named by "
                + "icechunk.native.path or icechunk.native.dir, or on java.library.path");
    }

    /** {@link #ABI_VERSION} as the native library defines it; libraries from before the check lack it. */
    private static native int abiVersion();

    static native void close(long handle);

    static native void initializeLogs(String filter);

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

    static native void repositoryResetBranch(long repository, String name, String to, String from);

    static native void repositoryCreateTag(long repository, String name, String snapshot);

    static native void repositoryDeleteTag(long repository, String name);

    static native String repositoryAncestry(long repository, String versionJson);

    static native String repositoryLookupSnapshot(long repository, String id);

    static native String repositoryResolveVersion(long repository, String versionJson);

    static native String repositoryDiff(long repository, String fromJson, String toJson);

    static native String repositoryExpireSnapshots(long repository, String optionsJson);

    static native String repositoryGarbageCollect(long repository, String optionsJson);

    static native long repositoryReadonlySession(long repository, String versionJson);

    static native long repositoryWritableSession(long repository, String branch);

    static native String sessionSnapshotId(long session);

    static native String sessionBranch(long session);

    static native boolean sessionReadOnly(long session);

    static native boolean sessionHasUncommittedChanges(long session);

    static native String sessionCommit(long session, String message, String optionsJson);

    static native String sessionStatus(long session);

    static native void sessionDiscardChanges(long session);

    static native long sessionStore(long session);

    static native byte[] storeGet(long store, String key, long rangeKind, long a, long b);

    static native byte[][] storeGetPartialValues(long store, String[] keys, long[] ranges);

    static native void storeSet(long store, String key, byte[] value, int offset, int length, boolean onlyIfNew);

    static native void storeSetBuffer(
            long store, String key, ByteBuffer direct, int position, int length, boolean onlyIfNew);

    static native boolean storeExists(long store, String key);

    static native long storeGetSize(long store, String key);

    static native void storeDelete(long store, String key);

    static native void storeDeleteDir(long store, String prefix);

    static native long storeGetSizePrefix(long store, String prefix);

    static native void storeClear(long store);

    static native boolean storeIsEmpty(long store, String prefix);

    static native String[] storeList(long store, int mode, String prefix);

    static native boolean storeReadOnly(long store);
}
