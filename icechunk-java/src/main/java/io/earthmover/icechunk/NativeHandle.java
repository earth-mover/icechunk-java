package io.earthmover.icechunk;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Java object backed by an entry in the native handle table.
 *
 * <p>Closing is idempotent and thread-safe. Calls that are already running when the handle closes finish normally,
 * because the native side holds its own reference for the duration of each call; later calls throw
 * {@link IllegalStateException}. An unreachable object that was never closed is released by a {@link Cleaner}, but
 * code should not rely on that: native objects can hold network connections and caches.
 */
abstract class NativeHandle implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final Release release;
    private final Cleaner.Cleanable cleanable;

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
        this.cleanable = CLEANER.register(this, release);
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

    @Override
    public void close() {
        cleanable.clean();
    }
}
