package io.earthmover.icechunk;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The Java side of refreshable credentials: runs a user's {@link Supplier} when icechunk asks for fresh credentials.
 *
 * <p>The native fetcher calls {@link #request(long)} from whichever thread needs credentials, which may be one of the
 * runtime's own threads, attached to the JVM only for that call. So {@code request} only queues the supplier on
 * {@link #EXECUTOR} and returns. The executor's thread runs the supplier, which may block on HTTP, and reports the
 * result with {@link Native#credentialsComplete}. User code therefore never runs on a runtime thread, and never on the
 * Java thread whose call is waiting for the credentials, so a supplier may itself use icechunk.
 */
final class CredentialsCallback {
    /**
     * Daemon threads, created on demand and stopped after a minute idle, so an idle process keeps none and the JVM
     * can exit while a supplier is still running.
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new DaemonThreads());

    private final Supplier<?> supplier;
    private final Kind kind;

    enum Kind {
        S3,
        GCS
    }

    CredentialsCallback(Supplier<?> supplier, Kind kind) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
        this.kind = kind;
    }

    /** Called from native code: queue the supplier and return at once. */
    void request(long id) {
        EXECUTOR.execute(() -> run(id));
    }

    private void run(long id) {
        String json = null;
        String error = null;
        try {
            json = toJson(supplier.get());
        } catch (Throwable t) {
            error = "credentials supplier failed: " + t;
        }
        Native.credentialsComplete(id, json, error);
    }

    private String toJson(Object credentials) {
        if (credentials == null) {
            throw new IllegalStateException("the supplier returned null");
        }
        switch (kind) {
            case S3:
                if (!(credentials instanceof S3Credentials)) {
                    throw new IllegalStateException("expected S3Credentials, got " + credentials.getClass());
                }
                return ((S3Credentials) credentials).toJson().toString();
            case GCS:
                if (!(credentials instanceof GcsCredentials)) {
                    throw new IllegalStateException("expected GcsCredentials, got " + credentials.getClass());
                }
                return ((GcsCredentials) credentials).toJson().toString();
            default:
                throw new IllegalStateException("unknown credentials kind " + kind);
        }
    }

    private static final class DaemonThreads implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "icechunk-credentials-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
