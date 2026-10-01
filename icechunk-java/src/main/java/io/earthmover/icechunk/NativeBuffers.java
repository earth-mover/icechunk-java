package io.earthmover.icechunk;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

/**
 * Tracks the direct buffers that expose icechunk's native memory to Java without copying.
 *
 * <p>Each buffer's native memory is released by a {@link Cleaner} once the buffer, and every slice or duplicate of
 * it, is unreachable. The garbage collector does not see that native memory, so a program with a large heap could
 * hold gigabytes of released-but-uncollected buffers before a collection happens. When the bytes lent to Java pass a
 * limit, this class requests a collection and waits briefly for cleaners to run, the same way the JDK manages its own
 * direct buffers.
 *
 * <p>The limit is the {@code icechunk.buffers.limitBytes} system property, or the maximum heap size if unset.
 */
final class NativeBuffers {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final long LIMIT =
            Long.getLong("icechunk.buffers.limitBytes", Runtime.getRuntime().maxMemory());

    /** Outstanding bytes above which the next lend triggers a collection. Raised after each attempt. */
    private static volatile long threshold = LIMIT;

    private NativeBuffers() {}

    /** Register the owner of a lent buffer and return the read-only view handed to callers. */
    static ByteBuffer adopt(ByteBuffer buffer, long owner) {
        if (owner != 0) {
            CLEANER.register(buffer, () -> Native.bufferRelease(owner));
        }
        return buffer.asReadOnlyBuffer();
    }

    /** Called after lending with the total bytes now outstanding. */
    static void afterLend(long outstanding) {
        if (outstanding > threshold) {
            collect();
        }
    }

    private static synchronized void collect() {
        if (Native.bufferOutstanding() <= threshold) {
            return;
        }
        System.gc();
        long sleep = 1;
        for (int attempt = 0; attempt < 7 && Native.bufferOutstanding() > LIMIT; attempt++) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            sleep *= 2;
        }
        // Whatever is still outstanding is reachable. Wait for half a limit more before
        // trying again, so a program that legitimately holds many buffers is not stalled
        // by a collection on every read.
        threshold = Math.max(LIMIT, Native.bufferOutstanding() + LIMIT / 2);
    }
}
