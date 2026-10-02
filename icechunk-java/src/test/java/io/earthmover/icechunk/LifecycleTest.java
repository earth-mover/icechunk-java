package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LifecycleTest {
    @Test
    void closedObjectsThrowIllegalState() {
        Storage storage = Storage.inMemory();
        Repository repo = Repository.create(storage);
        Session session = repo.writableSession("main");
        Store store = session.store();

        session.close();
        assertTrue(store.isClosed(), "closing a session closes its store");
        assertThrows(IllegalStateException.class, () -> store.get("zarr.json"));
        assertThrows(IllegalStateException.class, session::snapshotId);

        repo.close();
        repo.close();
        assertThrows(IllegalStateException.class, repo::listBranches);
        storage.close();
    }

    @Test
    void sessionsOutliveTheirRepositoryAndStorage() {
        Storage storage = Storage.inMemory();
        Repository repo = Repository.create(storage);
        try (Session session = repo.writableSession("main")) {
            repo.close();
            storage.close();
            session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
            assertTrue(session.store().exists("zarr.json"));
        }
    }

    /**
     * Closing a store while other threads are using it must never crash the JVM. Each call either completes or fails
     * with IllegalStateException.
     */
    @Test
    void closeRacesWithReads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                try (Storage storage = Storage.inMemory();
                        Repository repo = Repository.create(storage);
                        Session session = repo.writableSession("main")) {
                    Store store = session.store();
                    store.set("zarr.json", ByteBuffer.wrap(GROUP));
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicInteger unexpected = new AtomicInteger();
                    List<Future<?>> readers = new ArrayList<>();
                    for (int t = 0; t < 8; t++) {
                        readers.add(pool.submit(() -> {
                            start.await();
                            for (int i = 0; i < 50; i++) {
                                try {
                                    store.get("zarr.json");
                                } catch (IllegalStateException closed) {
                                    return null;
                                } catch (RuntimeException e) {
                                    unexpected.incrementAndGet();
                                }
                            }
                            return null;
                        }));
                    }
                    start.countDown();
                    store.close();
                    for (Future<?> reader : readers) {
                        reader.get(30, TimeUnit.SECONDS);
                    }
                    assertEquals(0, unexpected.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Only the multi-release jar on Java 9 or later carries the cleaner; the class directory and Java 8 use the class
     * that never releases on its own.
     */
    @Test
    void cleanerMatchesTheRuntime() {
        boolean fromJar = HandleCleaner.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getPath()
                .endsWith(".jar");
        boolean java9OrLater = !System.getProperty("java.specification.version").startsWith("1.");
        assertEquals(fromJar && java9OrLater, HandleCleaner.releasesUnreachable());
    }

    @Test
    void cleanerRunsReleaseForUnreachableOwners() throws InterruptedException {
        assumeTrue(HandleCleaner.releasesUnreachable());
        AtomicInteger released = new AtomicInteger();
        HandleCleaner.register(new Object(), released::incrementAndGet);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (released.get() == 0 && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }
        assertEquals(1, released.get());
    }

    /**
     * Sizing a prefix while another thread writes and deletes must neither deadlock on the session lock nor fail on a
     * key deleted after it was listed.
     */
    @Test
    void getSizePrefixWithConcurrentWrites() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage);
                Session session = repo.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", ByteBuffer.wrap(GROUP));
            store.set("data/zarr.json", ByteBuffer.wrap(StoreTest.ARRAY));
            Future<?> sizer = pool.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    store.getSizePrefix("");
                }
            });
            Future<?> writer = pool.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    store.set("data/c/0", ByteBuffer.wrap(StoreTest.CHUNK));
                    store.set("g" + i % 4 + "/zarr.json", ByteBuffer.wrap(GROUP));
                    store.delete("data/c/0");
                    store.deleteDir("g" + (i + 2) % 4);
                }
            });
            sizer.get(60, TimeUnit.SECONDS);
            writer.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void extensionHandlesCloseTheNativeObject() {
        Storage storage = Storage.inMemory();
        NativeExtensions.Handle handle = NativeExtensions.handle(storage.handle(), "Thing");
        assertEquals(storage.handle(), handle.get());
        handle.close();
        assertTrue(handle.isClosed());
        IllegalStateException e = assertThrows(IllegalStateException.class, handle::get);
        assertEquals("Thing is closed", e.getMessage());
        assertThrows(IllegalStateException.class, () -> Repository.create(storage));
        storage.close();
    }
}
