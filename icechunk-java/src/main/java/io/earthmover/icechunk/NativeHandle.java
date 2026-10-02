package io.earthmover.icechunk;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Java object backed by an entry in the native handle table.
 *
 * <p>Closing is idempotent and thread-safe. Calls that are already running when the handle closes finish normally,
 * because the native side holds its own reference for the duration of each call; later calls throw
 * {@link IllegalStateException}. On Java 9 and later, an unreachable object that was never closed is released by
 * {@link HandleCleaner}, but code should not rely on that: native objects can hold network connections and caches.
 *
 * <p>Subclasses pass {@link #handle()} to a native method and then call {@code HandleCleaner.reachabilityFence(this)}
 * in a {@code finally} block. Without the fence, the JIT may treat the object as unreachable as soon as the handle has
 * been read, and the cleaner could close the handle while the native call is still looking it up.
 */
abstract class NativeHandle implements AutoCloseable {
    private final Release release;
    private final Runnable cleanable;

    /** Kept separate from the outer object so the cleaner does not keep it reachable. */
    private static final class Release implements Runnable {
        private final long handle;
        private final AtomicBoolean closed = new AtomicBoolean();

        Release(long handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            if (closed.compareAndSet(false, true)) {
                Native.close(handle);
            }
        }
    }

    NativeHandle(long handle) {
        this.release = new Release(handle);
        this.cleanable = HandleCleaner.register(this, release);
    }

    final long handle() {
        if (release.closed.get()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }
        return release.handle;
    }

    /** Returns true once {@link #close} has been called. */
    public final boolean isClosed() {
        return release.closed.get();
    }

    /** Release the native object. Closing again does nothing. */
    @Override
    public void close() {
        cleanable.run();
    }
}
