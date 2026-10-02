package io.earthmover.icechunk;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;

/**
 * Releases native handles whose Java objects were never closed.
 *
 * <p>This is the Java 9 and later implementation in the multi-release jar. Code should not rely on it: native objects
 * can hold network connections and caches, and release waits for a garbage collection.
 */
final class HandleCleaner {
    private static final Cleaner CLEANER = Cleaner.create();

    private HandleCleaner() {}

    /** Whether unreachable handles are released without a call to {@code close}. */
    static boolean releasesUnreachable() {
        return true;
    }

    /**
     * Returns the action that {@code close} runs. It also runs once {@code owner} is unreachable, so {@code release}
     * must not refer to {@code owner}, and must be safe to run more than once.
     */
    static Runnable register(Object owner, Runnable release) {
        return CLEANER.register(owner, release)::clean;
    }

    /** Keeps {@code object} reachable until this call, so its handle cannot be released by the garbage collector. */
    @SuppressWarnings("ReachabilityFenceUsage") // callers invoke this wrapper from a finally block
    static void reachabilityFence(Object object) {
        Reference.reachabilityFence(object);
    }
}
