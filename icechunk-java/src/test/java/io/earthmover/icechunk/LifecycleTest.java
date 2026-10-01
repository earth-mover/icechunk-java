package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            session.store().set("zarr.json", GROUP);
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
                    store.set("zarr.json", GROUP);
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

    @Test
    void interruptedCallsThrowAndKeepTheFlag() {
        try (Storage storage = Storage.inMemory()) {
            Thread.currentThread().interrupt();
            try {
                IcechunkException e = assertThrows(IcechunkException.class, () -> Repository.create(storage));
                assertTrue(e.getCause() instanceof InterruptedException);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }
}
