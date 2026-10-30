package io.earthmover.icechunk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Credentials refreshed through a Java {@link Supplier}.
 *
 * <p>The tests that talk to S3 need an S3-compatible server, named by {@code -Dicechunk.tests.s3.endpoint} (for
 * example {@code http://127.0.0.1:9000} running moto or MinIO), with access key {@code testkey} / {@code testsecret}.
 * They skip without one. moto ignores signatures, so they check that the supplier is called and that operations keep
 * working across expiry, not that the server rejects stale credentials.
 */
final class RefreshableCredentialsTest {
    private static final String ENDPOINT = System.getProperty("icechunk.tests.s3.endpoint", "");
    private static final String BUCKET = "refreshable";

    @Test
    void refreshableCredentialsTravelAsACallbackReference() {
        S3Credentials credentials = S3Credentials.refreshable(() -> S3Credentials.of("a", "s"));
        assertEquals(
                "{\"type\":\"refreshable\",\"callback\":0}",
                credentials.toJson().toString());
        assertEquals(1, credentials.callbacks().length);
        assertEquals("S3Credentials(refreshable)", credentials.toString());
        assertEquals(
                "{\"type\":\"refreshable\",\"callback\":0,\"timeout_seconds\":2}",
                S3Credentials.refreshable(() -> null, Duration.ofMillis(1500))
                        .toJson()
                        .toString());
        assertEquals(0, S3Credentials.of("a", "s").callbacks().length);
        assertEquals(
                "{\"type\":\"static\",\"access_key_id\":\"a\",\"secret_access_key\":\"s\","
                        + "\"expires_after\":\"2030-01-01T00:00:00Z\"}",
                S3Credentials.of("a", "s", null, Instant.parse("2030-01-01T00:00:00Z"))
                        .toJson()
                        .toString());
        assertThrows(IllegalArgumentException.class, () -> S3Credentials.refreshable(() -> null, Duration.ZERO));
    }

    @Test
    void virtualChunkContainersRejectRefreshableCredentials() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Credentials.s3(S3Credentials.refreshable(() -> S3Credentials.of("a", "s"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> Credentials.gcs(GcsCredentials.refreshable(() -> GcsCredentials.bearerToken("t"))));
    }

    @Test
    void storageOpensWithoutCallingTheSupplier() {
        AtomicInteger calls = new AtomicInteger();
        try (Storage storage = Storage.s3(S3Options.builder("bucket")
                .region("us-east-1")
                .credentials(S3Credentials.refreshable(() -> {
                    calls.incrementAndGet();
                    return S3Credentials.of("a", "s");
                }))
                .build())) {
            assertEquals("Storage(s3)", storage.toString());
        }
        try (Storage storage = Storage.gcs(GcsOptions.builder("bucket")
                .credentials(GcsCredentials.refreshable(() -> GcsCredentials.bearerToken("t")))
                .build())) {
            assertEquals("Storage(gcs)", storage.toString());
        }
        assertEquals(0, calls.get());
    }

    // --- against an S3-compatible server -------------------------------------------------------------------------

    /** Credentials that the AWS SDK's cache treats as expiring {@code seconds} from now. */
    private static Supplier<S3Credentials> counting(AtomicInteger calls, long seconds) {
        return () -> {
            calls.incrementAndGet();
            return S3Credentials.of("testkey", "testsecret", null, Instant.now().plusSeconds(seconds));
        };
    }

    private static Storage storage(Supplier<S3Credentials> supplier, Duration timeout) {
        requireS3();
        return Storage.s3(S3Options.builder(BUCKET)
                .prefix("t-" + UUID.randomUUID())
                .region("us-east-1")
                .endpointUrl(ENDPOINT)
                .allowHttp(true)
                .forcePathStyle(true)
                .credentials(S3Credentials.refreshable(supplier, timeout))
                .build());
    }

    private static void requireS3() {
        TestEnvironment.require(!ENDPOINT.isEmpty(), "an S3 endpoint (icechunk.tests.s3.endpoint)");
        try {
            HttpURLConnection put = (HttpURLConnection) new URL(ENDPOINT + "/" + BUCKET).openConnection();
            put.setRequestMethod("PUT");
            put.getResponseCode(); // 200, or 409 when the bucket exists
            put.disconnect();
        } catch (java.io.IOException e) {
            TestEnvironment.require(false, "S3 endpoint " + ENDPOINT + " (" + e + ")");
        }
    }

    /** Reads on {@code threads} threads for {@code seconds}, each read opening a session from storage. */
    private static int readConcurrently(Repository repo, int threads, int seconds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                int reads = 0;
                while (System.nanoTime() < until) {
                    // A new session reads the branch and snapshot from storage each time.
                    try (Session session = repo.readonlySession(Version.branch("main"))) {
                        assertTrue(session.store().exists("zarr.json"));
                    }
                    reads++;
                }
                return reads;
            }));
        }
        int reads = 0;
        for (Future<Integer> result : results) {
            reads += result.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return reads;
    }

    private static void writeRoot(Repository repo) {
        try (Session session = repo.writableSession("main")) {
            session.store()
                    .set("zarr.json", ByteBuffer.wrap("{\"zarr_format\":3,\"node_type\":\"group\"}".getBytes(UTF_8)));
            session.commit("root");
        }
    }

    @Test
    void credentialsAreCachedUntilShortlyBeforeTheyExpire() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // icechunk's AWS identity cache fetches new credentials 120 seconds before expiry, so credentials that
        // expire in 123 seconds are good for about 3 seconds: over 7 seconds, a few fetches, not one per request.
        try (Storage storage = storage(counting(calls, 123), null);
                Repository repo = Repository.create(storage)) {
            writeRoot(repo);
            int reads = readConcurrently(repo, 8, 7);
            System.out.printf(
                    "refresh under load: %d reads on 8 threads, supplier called %d times%n", reads, calls.get());
            assertTrue(calls.get() >= 2, "the supplier ran only " + calls.get() + " times");
            assertTrue(calls.get() <= 10, "credentials were not cached: " + calls.get() + " fetches");
            assertTrue(reads > 8 * calls.get(), "reads: " + reads);
        }
    }

    @Test
    void manyThreadsSurviveASupplierCalledOnEveryRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // Inside the 120-second refresh window, so every request asks the supplier again: thousands of callback
        // round trips from runtime and Java threads at once.
        try (Storage storage = storage(counting(calls, 11), null);
                Repository repo = Repository.create(storage)) {
            writeRoot(repo);
            int reads = readConcurrently(repo, 8, 5);
            System.out.printf("callback stress: %d reads on 8 threads, supplier called %d times%n", reads, calls.get());
            assertTrue(calls.get() >= reads, "calls " + calls.get() + " < reads " + reads);
        }
    }

    @Test
    void aFailingSupplierFailsTheOperationNotTheJvm() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<S3Credentials> failing = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("no credentials today");
        };
        try (Storage storage = storage(failing, null)) {
            IcechunkException e = assertThrows(IcechunkException.class, () -> Repository.create(storage));
            assertTrue(calls.get() >= 1);
            assertTrue(e.getMessage().contains("no credentials today"), e.getMessage());
        }
        // The process is fine and later storages work.
        try (Storage storage = storage(counting(new AtomicInteger(), 3600), null);
                Repository repo = Repository.create(storage)) {
            assertArrayEquals(new String[] {"main"}, repo.listBranches().toArray(new String[0]));
        }
    }

    @Test
    void aSupplierThatReturnsTheWrongTypeIsReported() {
        Supplier<S3Credentials> anonymous = S3Credentials::anonymous;
        try (Storage storage = storage(anonymous, null)) {
            IcechunkException e = assertThrows(IcechunkException.class, () -> Repository.create(storage));
            assertTrue(e.getMessage().contains("S3Credentials.of"), e.getMessage());
        }
    }

    @Test
    void aSupplierThatNeverAnswersTimesOut() {
        CountDownLatch never = new CountDownLatch(1);
        Supplier<S3Credentials> hanging = () -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return S3Credentials.of("testkey", "testsecret");
        };
        long start = System.nanoTime();
        try (Storage storage = storage(hanging, Duration.ofSeconds(1))) {
            IcechunkException e = assertThrows(IcechunkException.class, () -> Repository.create(storage));
            assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
        } finally {
            never.countDown();
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(60), "the timeout did not cut it off");
    }

    @Test
    void aSlowSupplierBlocksOnlyTheCallsThatNeedCredentials() throws Exception {
        CountDownLatch supplierStarted = new CountDownLatch(1);
        Supplier<S3Credentials> slow = () -> {
            supplierStarted.countDown();
            try {
                Thread.sleep(2000); // an HTTP request to a credentials service
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return S3Credentials.of("testkey", "testsecret", null, Instant.now().plusSeconds(3600));
        };
        try (Storage s3 = storage(slow, null);
                Storage memory = Storage.inMemory();
                Repository other = Repository.create(memory)) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<Repository> created = pool.submit(() -> Repository.create(s3));
            assertTrue(supplierStarted.await(30, TimeUnit.SECONDS));
            // While the supplier sleeps, icechunk keeps serving other calls promptly.
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                assertEquals(1, other.listBranches().size());
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            System.out.printf("slow supplier: 20 unrelated calls took %d ms while it slept%n", elapsedMs);
            assertTrue(elapsedMs < 1000, "other calls took " + elapsedMs + " ms while the supplier slept");
            try (Repository repo = created.get(60, TimeUnit.SECONDS)) {
                assertEquals(1, repo.listBranches().size());
            }
            pool.shutdown();
        }
    }

    @Test
    void aSupplierMayItselfUseIcechunk() {
        Supplier<S3Credentials> reentrant = () -> {
            // Runs on an icechunk-credentials thread, not the waiting caller's, so this does not deadlock.
            try (Storage memory = Storage.inMemory();
                    Repository repo = Repository.create(memory)) {
                assertEquals(1, repo.listBranches().size());
            }
            return S3Credentials.of("testkey", "testsecret", null, Instant.now().plusSeconds(3600));
        };
        try (Storage storage = storage(reentrant, null);
                Repository repo = Repository.create(storage)) {
            assertEquals(1, repo.listBranches().size());
        }
    }

    @Test
    void theSupplierIsReleasedWhenNothingUsesItAnyMore() throws Exception {
        WeakReference<Supplier<S3Credentials>> weak = openAndClose();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (weak.get() != null && System.nanoTime() < until) {
            System.gc();
            // Closed native objects are freed once no thread is inside a native call; a call moves that along.
            try (Storage memory = Storage.inMemory()) {
                assertEquals("Storage(in-memory)", memory.toString());
            }
            Thread.sleep(50);
        }
        assertNull(weak.get(), "the native side still holds the supplier");
    }

    private static WeakReference<Supplier<S3Credentials>> openAndClose() {
        Supplier<S3Credentials> supplier = counting(new AtomicInteger(), 3600);
        try (Storage storage = storage(supplier, null);
                Repository repo = Repository.create(storage);
                Session session = repo.writableSession("main")) {
            session.store()
                    .set("zarr.json", ByteBuffer.wrap("{\"zarr_format\":3,\"node_type\":\"group\"}".getBytes(UTF_8)));
            session.commit("root");
        }
        return new WeakReference<>(supplier);
    }
}
