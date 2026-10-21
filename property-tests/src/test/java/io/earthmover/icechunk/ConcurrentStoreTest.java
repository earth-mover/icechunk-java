package io.earthmover.icechunk;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.ConcurrentPool;
import dev.hegel.HegelTest;
import dev.hegel.Invariant;
import dev.hegel.Rule;
import dev.hegel.Stateful;
import dev.hegel.TestCase;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Several threads using one {@link Store} at once, which its documentation says is safe. Within a round, threads write
 * new chunks, delete chunks written in earlier rounds, and read and list chunks that are never deleted, so the
 * expected contents do not depend on how the threads interleave. Commits run in rounds of their own. Between rounds
 * the whole store is compared with the model.
 */
class ConcurrentStoreTest {
    private static final String ARRAY = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[1000000],"
            + "\"data_type\":\"uint8\",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[1]}},"
            + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
            + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}";

    @HegelTest
    void concurrentStoreMatchesModel(TestCase tc) {
        try (ConcurrentStoreMachine machine = new ConcurrentStoreMachine(tc)) {
            Stateful.run(machine, tc, Stateful.options().maxConcurrency(4));
        }
    }

    static final class ConcurrentStoreMachine implements AutoCloseable {
        private final Storage storage = Storage.inMemory();
        private final Repository repo;
        private volatile Session session;
        private volatile Store store;

        private final Map<String, byte[]> model = new ConcurrentHashMap<>();
        private final AtomicInteger nextChunk = new AtomicInteger();
        /** Chunks no rule deletes, which readers can check while writers run. */
        private final ConcurrentPool<String> kept;
        /** Chunks a later round may delete. */
        private final ConcurrentPool<String> deletable;

        ConcurrentStoreMachine(TestCase tc) {
            String config = tc.draw(sampledFrom("{}", "{\"inline_chunk_threshold_bytes\":0}"), "config");
            repo = Repository.create(
                    storage, RepositoryOptions.builder().configJson(config).build());
            session = repo.writableSession("main");
            store = session.store();
            kept = new ConcurrentPool<>(tc);
            deletable = new ConcurrentPool<>(tc);
            put("zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}".getBytes(UTF_8));
            put("a/zarr.json", ARRAY.getBytes(UTF_8));
            // Readers draw from kept chunks, so start with some.
            for (int i = 0; i < 3; i++) {
                String key = "a/c/" + nextChunk.getAndIncrement();
                put(key, new byte[] {(byte) i});
                kept.add(tc, key);
            }
        }

        @Rule(group = "write", weight = 3)
        void writeChunk(TestCase tc) {
            String key = "a/c/" + nextChunk.getAndIncrement();
            byte[] value = TestValues.draw(tc, 1);
            TestValues.BufferKind kind = tc.draw(sampledFrom(TestValues.BufferKind.values()), "buffer");
            tc.note("set " + key + " (" + value.length + " bytes, " + kind + ")");
            store.set(key, kind.wrap(value));
            model.put(key, value);
            if (tc.draw(booleans(), "keep")) {
                kept.add(tc, key);
            } else {
                deletable.add(tc, key);
            }
        }

        @Rule(group = "write")
        void deleteChunk(TestCase tc) {
            // Another worker can empty the pool between this check and the draw, and the draw then rejects the
            // rule. That is rare.
            if (deletable.isEmpty()) {
                return;
            }
            String key = tc.draw(deletable.consuming(), "deleted");
            store.delete(key);
            model.remove(key);
        }

        @Rule(group = "write", weight = 2)
        void readChunk(TestCase tc) {
            String key = tc.draw(kept.reusable(), "read");
            assertArrayEquals(model.get(key), store.get(key).orElseThrow(), key);
        }

        @Rule(group = "write")
        void readChunks(TestCase tc) {
            int n = tc.draw(integers().min(1).max(5), "count");
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys.add(tc.draw(kept.reusable(), "read"));
            }
            List<Optional<byte[]>> values = store.getPartialValues(keys);
            for (int i = 0; i < n; i++) {
                assertArrayEquals(model.get(keys.get(i)), values.get(i).orElseThrow(), keys.get(i));
            }
        }

        @Rule(group = "write")
        void listContainsKeptChunk(TestCase tc) {
            String key = tc.draw(kept.reusable(), "listed");
            assertTrue(store.list().contains(key), key + " is not listed");
            assertTrue(store.listDir("a/c").contains(key.substring("a/c/".length())), key + " is not in listDir");
        }

        @Rule(group = "commit")
        synchronized void commit(TestCase tc) {
            // Every worker in a commit round runs this; only the first has changes to commit.
            if (!session.hasUncommittedChanges()) {
                return;
            }
            SnapshotId id = session.commit("commit");
            tc.note("committed " + id);
            Map<String, byte[]> expected = new TreeMap<>(model);
            try (Session readonly = repo.readonlySession(Version.snapshot(id))) {
                assertContents(readonly.store(), expected);
            }
            Session old = session;
            session = repo.writableSession("main");
            store = session.store();
            old.close();
            assertFalse(session.hasUncommittedChanges());
        }

        @Invariant(alwaysRun = true)
        void storeMatchesModel(TestCase tc) {
            assertContents(store, model);
        }

        private void put(String key, byte[] value) {
            store.set(key, ByteBuffer.wrap(value));
            model.put(key, value);
        }

        private static void assertContents(Store actual, Map<String, byte[]> expected) {
            List<String> keys = new ArrayList<>(actual.list());
            keys.sort(null);
            assertEquals(new ArrayList<>(new TreeMap<>(expected).keySet()), keys, "list()");
            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                if (!entry.getKey().endsWith("zarr.json")) {
                    assertArrayEquals(
                            entry.getValue(), actual.get(entry.getKey()).orElseThrow(), entry.getKey());
                }
            }
        }

        @Override
        public void close() {
            session.close();
            repo.close();
            storage.close();
        }
    }
}
