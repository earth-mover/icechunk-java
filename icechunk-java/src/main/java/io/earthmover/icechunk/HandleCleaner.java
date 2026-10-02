package io.earthmover.icechunk;

/**
 * Releases native handles whose Java objects were never closed, on JVMs that can do so safely.
 *
 * <p>This is the Java 8 implementation, which never releases a handle on its own: Java 8 has no
 * {@code Reference.reachabilityFence}, and without it the JIT may collect an object while a native call is still using
 * its handle. Unclosed handles therefore stay open until the JVM exits. The multi-release jar replaces this class on
 * Java 9 and later with one backed by {@code java.lang.ref.Cleaner}.
 */
final class HandleCleaner {
    private HandleCleaner() {}

    /** Whether unreachable handles are released without a call to {@code close}. */
    static boolean releasesUnreachable() {
        return false;
    }

    /**
     * Returns the action that {@code close} runs. Implementations may also run it once {@code owner} is unreachable,
     * so {@code release} must not refer to {@code owner}, and must be safe to run more than once.
     */
    static Runnable register(Object owner, Runnable release) {
        return release;
    }

    /** Keeps {@code object} reachable until this call, so its handle cannot be released by the garbage collector. */
    static void reachabilityFence(Object object) {}
}
