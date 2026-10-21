package io.earthmover.icechunk;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.Invariant;
import dev.hegel.Rule;
import dev.hegel.Stateful;
import dev.hegel.TestCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Random sequences of {@link Store} and {@link Session} operations, checked after every step against a model of the
 * session's keys and values. A port of the hierarchy state machine in icechunk-python's
 * {@code tests/test_zarr/test_stateful.py}, aimed at what the Java side adds: passing keys and values over JNI from
 * every kind of {@link ByteBuffer}, ranges, listings, and handles that outlive sessions and repositories.
 *
 * <p>A failure prints the shrunk rule sequence and the draws of each rule. Set {@code HEGEL_TEST_CASES} for a longer
 * run.
 */
class StoreStateMachineTest {
    /** A path that is never a node. */
    private static final String MISSING_ARRAY = "zz";

    /** An array that every state has, so a rule that writes chunks always has somewhere to write them. */
    private static final String DATA = "data";

    private static final List<String> NAMES = List.of("a", "b", "c", "x y", "é", "😀");

    @HegelTest
    void storeMatchesModel(TestCase tc) {
        try (StoreMachine machine = new StoreMachine(tc)) {
            Stateful.run(machine, tc);
        }
    }

    /** The system under test, the model it should match, and the rules that change both. */
    static final class StoreMachine implements AutoCloseable {
        private final Path dir;
        private final Storage storage;
        private Repository repo;
        private Session session;
        private Store store;

        /** The keys and values the session should hold. */
        private SortedMap<String, byte[]> model = new TreeMap<>();
        /** The keys and values of the branch tip. */
        private SortedMap<String, byte[]> committed = new TreeMap<>();
        /** The keys and values of every commit, oldest first. */
        private final Map<SnapshotId, SortedMap<String, byte[]>> commits = new LinkedHashMap<>();

        StoreMachine(TestCase tc) {
            boolean filesystem = tc.draw(booleans(), "filesystem");
            int specVersion = tc.draw(integers().min(1).max(2), "specVersion");
            String config = tc.draw(sampledFrom("{}", "{\"inline_chunk_threshold_bytes\":0}"), "config");
            try {
                dir = filesystem ? Files.createTempDirectory("icechunk-stateful") : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            storage = filesystem ? Storage.localFilesystem(dir) : Storage.inMemory();
            repo = Repository.create(
                    storage,
                    RepositoryOptions.builder()
                            .specVersion(specVersion)
                            .configJson(config)
                            .build());
            commits.put(repo.lookupBranch("main"), new TreeMap<>());
            session = repo.writableSession("main");
            store = session.store();
            // Committed, so discarding changes or reopening the session keeps them.
            addRoot();
            commits.put(session.commit("root"), copy(model));
            committed = copy(model);
            session.close();
            session = repo.writableSession("main");
            store = session.store();
        }

        @Rule
        void addGroup(TestCase tc) {
            // A group may replace a group, which changes its attributes, but not an array.
            String path = childPath(tc, false);
            if (path == null) {
                return;
            }
            put(path + "/zarr.json", group(tc.draw(integers().min(0).max(3), "attribute")));
        }

        @Rule(weight = 2)
        void addArray(TestCase tc) {
            String path = childPath(tc, true);
            if (path == null) {
                return;
            }
            int rank = tc.draw(integers().min(1).max(2), "rank");
            long[] shape = new long[rank];
            long[] chunks = new long[rank];
            for (int i = 0; i < rank; i++) {
                shape[i] = tc.draw(integers().min(1).max(6), "shape");
                chunks[i] = tc.draw(integers().min(1).max(3), "chunks");
            }
            put(path + "/zarr.json", array(shape, chunks));
        }

        @Rule(weight = 3)
        void writeChunk(TestCase tc) {
            write(tc, false);
        }

        @Rule
        void writeChunkIfNotExists(TestCase tc) {
            write(tc, true);
        }

        private void write(TestCase tc, boolean onlyIfNew) {
            String path = tc.draw(sampledFrom(arrays()), "array");
            String key = path + "/c/" + chunkIndex(tc, path);
            byte[] value = TestValues.draw(tc, 0);
            TestValues.BufferKind kind = tc.draw(sampledFrom(TestValues.BufferKind.values()), "buffer");
            tc.note((onlyIfNew ? "set if not exists " : "set ") + key + " (" + value.length + " bytes, " + kind + ")");
            ByteBuffer buffer = kind.wrap(value);
            int position = buffer.position();
            if (value.length == 0 && !(onlyIfNew && model.containsKey(key))) {
                // No chunk decodes to zero bytes, so icechunk refuses to store one, unless the write is skipped.
                assertThrows(
                        IcechunkException.class,
                        () -> {
                            if (onlyIfNew) {
                                store.setIfNotExists(key, buffer);
                            } else {
                                store.set(key, buffer);
                            }
                        },
                        "a zero-length chunk was stored");
            } else if (onlyIfNew) {
                store.setIfNotExists(key, buffer);
                model.putIfAbsent(key, value);
            } else {
                store.set(key, buffer);
                model.put(key, value);
            }
            assertEquals(position, buffer.position(), "set moved the buffer's position");
        }

        @Rule
        void deleteKey(TestCase tc) {
            List<String> keys = new ArrayList<>();
            for (String key : model.keySet()) {
                if (!key.endsWith("zarr.json")) {
                    keys.add(key);
                }
            }
            keys.add(MISSING_ARRAY + "/c/0");
            String key = tc.draw(sampledFrom(keys), "key");
            store.delete(key);
            model.remove(key);
        }

        @Rule
        void deleteDir(TestCase tc) {
            List<String> prefixes = new ArrayList<>(nodes());
            prefixes.remove(DATA);
            for (String array : arrays()) {
                prefixes.add(array + "/c");
            }
            String prefix = tc.draw(sampledFrom(prefixes), "prefix");
            store.deleteDir(prefix);
            model.keySet().removeIf(key -> prefix.isEmpty() || key.startsWith(prefix + "/"));
            if (prefix.isEmpty()) {
                checkStore(store, model);
                addRoot();
            }
        }

        @Rule(weight = 2)
        void getRange(TestCase tc) {
            String key = readKey(tc);
            byte[] value = reference(key);
            ByteRange range = drawRange(tc, value == null ? 4 : value.length, key.endsWith("zarr.json"));
            if (value != null && !inBounds(key, value.length, range)) {
                assertThrows(IcechunkException.class, () -> store.get(key, range), key + " " + range);
                return;
            }
            assertValue(key + " " + range, slice(value, range), store.get(key, range));
        }

        @Rule
        void getPartialValues(TestCase tc) {
            int n = tc.draw(integers().min(1).max(5), "count");
            List<String> keys = new ArrayList<>();
            List<ByteRange> ranges = new ArrayList<>();
            boolean valid = true;
            for (int i = 0; i < n; i++) {
                String key = readKey(tc);
                byte[] value = reference(key);
                ByteRange range = drawRange(tc, value == null ? 4 : value.length, key.endsWith("zarr.json"));
                keys.add(key);
                ranges.add(range);
                valid &= value == null || inBounds(key, value.length, range);
            }
            if (!valid) {
                assertThrows(IcechunkException.class, () -> store.getPartialValues(keys, ranges));
                return;
            }
            List<Optional<byte[]>> values = store.getPartialValues(keys, ranges);
            assertEquals(n, values.size());
            for (int i = 0; i < n; i++) {
                assertValue(
                        keys.get(i) + " " + ranges.get(i), slice(reference(keys.get(i)), ranges.get(i)), values.get(i));
            }
        }

        @Rule(weight = 2)
        void commit(TestCase tc) {
            SnapshotId id = session.commit(
                    "commit " + commits.size(),
                    CommitOptions.builder()
                            .allowEmpty(!session.hasUncommittedChanges())
                            .build());
            tc.note("committed " + id);
            assertFalse(session.hasUncommittedChanges());
            committed = copy(model);
            commits.put(id, copy(model));
            assertEquals(id, repo.lookupBranch("main"));
            session.close();
            session = repo.writableSession("main");
            store = session.store();
        }

        @Rule
        void discardChanges(TestCase tc) {
            session.discardChanges();
            assertFalse(session.hasUncommittedChanges());
            model = copy(committed);
        }

        @Rule
        void reopenSession(TestCase tc) {
            session.close();
            assertThrows(IllegalStateException.class, () -> store.list());
            session = repo.writableSession("main");
            store = session.store();
            model = copy(committed);
        }

        @Rule
        void reopenRepository(TestCase tc) {
            repo.close();
            repo = Repository.open(storage);
        }

        @Rule
        void readOldSnapshot(TestCase tc) {
            SnapshotId id = tc.draw(sampledFrom(new ArrayList<>(commits.keySet())), "snapshot");
            try (Session readonly = repo.readonlySession(Version.snapshot(id))) {
                Store old = readonly.store();
                assertTrue(old.isReadOnly());
                checkStore(old, commits.get(id));
                assertThrows(IcechunkException.class, () -> old.set("zarr.json", ByteBuffer.wrap(group(0))));
            }
        }

        @Rule
        void clear(TestCase tc) {
            store.clear();
            model.clear();
            checkStore(store, model);
            addRoot();
        }

        @Invariant(alwaysRun = true)
        void storeMatchesModel(TestCase tc) {
            checkStore(store, model);
            if (!sameContents(model, committed)) {
                assertTrue(session.hasUncommittedChanges(), "a changed session reports no changes");
            }
        }

        /** The root group and {@link #DATA}, which every state has. */
        private void addRoot() {
            put("zarr.json", group(0));
            put(DATA + "/zarr.json", array(new long[] {4}, new long[] {2}));
        }

        private void put(String key, byte[] value) {
            store.set(key, ByteBuffer.wrap(value));
            model.put(key, value);
        }

        /**
         * The value a range read of {@code key} is cut from. For a metadata document that is the store's own copy, as
         * icechunk serializes it in its own way; the invariant compares that copy with the model.
         */
        private byte[] reference(String key) {
            if (key.endsWith("zarr.json") && model.containsKey(key)) {
                return store.get(key).get();
            }
            return model.get(key);
        }

        /**
         * A path a new node can take: a child of a group that is not an array, and if {@code free}, not a node at all.
         * Null in the rare state where every name under every group is taken.
         */
        private String childPath(TestCase tc, boolean free) {
            List<String> paths = new ArrayList<>();
            for (String parent : nodes()) {
                if (isGroup(parent)) {
                    for (String name : NAMES) {
                        String path = parent.isEmpty() ? name : parent + "/" + name;
                        boolean exists = model.containsKey(path + "/zarr.json");
                        if (!exists || (!free && isGroup(path))) {
                            paths.add(path);
                        }
                    }
                }
            }
            if (paths.isEmpty()) {
                tc.note("every name is taken");
                return null;
            }
            return tc.draw(sampledFrom(paths), "path");
        }

        private String chunkIndex(TestCase tc, String array) {
            List<Object> shape = arrayField(array, "shape");
            List<Object> chunks = chunkShape(array);
            List<String> index = new ArrayList<>();
            for (int i = 0; i < shape.size(); i++) {
                long count = ceilDiv((Long) shape.get(i), (Long) chunks.get(i));
                index.add(String.valueOf(tc.draw(integers().min(0).max((int) count - 1), "index")));
            }
            return String.join("/", index);
        }

        /**
         * A key to read: a chunk the model holds, any chunk of an array, which may be missing, a chunk of an array that
         * does not exist, or a metadata document.
         */
        private String readKey(TestCase tc) {
            List<String> keys = new ArrayList<>();
            for (String key : model.keySet()) {
                if (!key.endsWith("zarr.json")) {
                    keys.add(key);
                }
            }
            List<String> arrays = arrays();
            String key;
            int choice = tc.draw(integers().min(0).max(3), "keyKind");
            if (choice == 3 && !nodes().isEmpty()) {
                key = metadataKey(tc.draw(sampledFrom(nodes()), "node"));
            } else if (choice == 0 && !keys.isEmpty()) {
                key = tc.draw(sampledFrom(keys), "key");
            } else if (choice == 1 && !arrays.isEmpty()) {
                String array = tc.draw(sampledFrom(arrays), "array");
                key = array + "/c/" + chunkIndex(tc, array);
            } else {
                key = MISSING_ARRAY + "/c/0";
            }
            tc.note("read " + key);
            return key;
        }

        /** Every node, by path, in path order; the root is {@code ""}. */
        private List<String> nodes() {
            List<String> nodes = new ArrayList<>();
            for (String key : model.keySet()) {
                if (key.equals("zarr.json")) {
                    nodes.add("");
                } else if (key.endsWith("/zarr.json")) {
                    nodes.add(key.substring(0, key.length() - "/zarr.json".length()));
                }
            }
            return nodes;
        }

        private List<String> arrays() {
            List<String> arrays = new ArrayList<>();
            for (String node : nodes()) {
                if (!isGroup(node)) {
                    arrays.add(node);
                }
            }
            return arrays;
        }

        private boolean isGroup(String node) {
            return "group".equals(metadata(node).get("node_type"));
        }

        private Map<String, Object> metadata(String node) {
            return JsonReader.readObject(new String(model.get(metadataKey(node)), UTF_8));
        }

        @SuppressWarnings("unchecked")
        private List<Object> arrayField(String array, String name) {
            return (List<Object>) metadata(array).get(name);
        }

        @SuppressWarnings("unchecked")
        private List<Object> chunkShape(String array) {
            Map<String, Object> grid = (Map<String, Object>) metadata(array).get("chunk_grid");
            return (List<Object>) ((Map<String, Object>) grid.get("configuration")).get("chunk_shape");
        }

        private void checkStore(Store actual, SortedMap<String, byte[]> expected) {
            assertEquals(new ArrayList<>(expected.keySet()), sorted(actual.list()), "list()");
            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                String key = entry.getKey();
                assertValue(key, Optional.of(entry.getValue()), actual.get(key));
                assertTrue(actual.exists(key), key + " does not exist");
                if (!key.endsWith("zarr.json")) {
                    assertEquals(OptionalLong.of(entry.getValue().length), actual.getSize(key), "size of " + key);
                }
            }
            SortedMap<String, byte[]> saved = model;
            model = expected;
            try {
                List<String> nodes = nodes();
                assertEquals(
                        new TreeSet<>(nodes), new TreeSet<>(actual.listNodes().keySet()), "listNodes()");
                for (String node : nodes) {
                    String prefix = node.isEmpty() ? "" : node + "/";
                    List<String> under = new ArrayList<>();
                    TreeSet<String> children = new TreeSet<>();
                    for (String key : expected.keySet()) {
                        if (key.startsWith(prefix)) {
                            under.add(key);
                            String rest = key.substring(prefix.length());
                            int slash = rest.indexOf('/');
                            children.add(slash < 0 ? rest : rest.substring(0, slash));
                        }
                    }
                    // icechunk lists an array's chunk directory even when it holds no chunks.
                    if (!isGroup(node)) {
                        children.add("c");
                    }
                    assertEquals(under, sorted(actual.listPrefix(node)), "listPrefix('" + node + "')");
                    assertEquals(new ArrayList<>(children), sorted(actual.listDir(node)), "listDir('" + node + "')");
                    assertFalse(actual.isEmpty(node), "isEmpty('" + node + "')");
                }
            } finally {
                model = saved;
            }
        }

        @Override
        public void close() {
            session.close();
            repo.close();
            storage.close();
            if (dir != null) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> path.toFile().delete());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /**
     * A range of each kind for the value of a key, {@code length} bytes long. Two kinds of read are left out until
     * icechunk fixes them; remove these limits then:
     *
     * <ul>
     *   <li>A metadata range past the end of the document, which panics inside icechunk 2.3.0. Metadata ranges stay
     *       within the document.
     *   <li>A chunk range that reads zero bytes, which icechunk 2.3.0 passes to the object store for a native chunk,
     *       and the store rejects; the same range of an inline chunk reads as empty. Chunk ranges read at least one
     *       byte, and may run past the end.
     * </ul>
     */
    private static ByteRange drawRange(TestCase tc, int length, boolean metadata) {
        int max = metadata ? length : length + 2;
        int min = metadata ? 0 : 1;
        ByteRange range = switch (tc.draw(integers().min(0).max(3), "rangeKind")) {
            case 0 -> ByteRange.all();
            case 1 -> {
                int start = tc.draw(integers().min(0).max(max), "start");
                int n = tc.draw(integers().min(Math.min(min, max - start)).max(max - start), "length");
                yield ByteRange.of(start, start + n);
            }
            case 2 -> ByteRange.from(tc.draw(integers().min(0).max(max), "offset"));
            default -> ByteRange.suffix(tc.draw(integers().min(min).max(max), "suffix"));
        };
        tc.note("range " + range);
        return range;
    }

    /**
     * Whether icechunk accepts {@code range} of the value of {@code key}, {@code length} bytes long. It does not clamp.
     * A chunk range must start inside the chunk and must not end or reach back past it; a metadata range may also
     * start at the end, and read nothing.
     */
    private static boolean inBounds(String key, long length, ByteRange range) {
        long startLimit = key.endsWith("zarr.json") ? length : length - 1;
        if (range.kind() == Native.RANGE_BOUNDED) {
            return range.a() <= startLimit && range.b() <= length;
        } else if (range.kind() == Native.RANGE_FROM) {
            return range.a() <= startLimit;
        }
        return range.a() <= length;
    }

    /** What reading {@code range} of {@code value} returns, for a range {@link #inBounds} accepts. */
    private static Optional<byte[]> slice(byte[] value, ByteRange range) {
        if (value == null) {
            return Optional.empty();
        }
        int n = value.length;
        if (range.kind() == Native.RANGE_BOUNDED) {
            return Optional.of(Arrays.copyOfRange(value, (int) range.a(), (int) range.b()));
        } else if (range.kind() == Native.RANGE_FROM) {
            return Optional.of(Arrays.copyOfRange(value, (int) range.a(), n));
        }
        return Optional.of(Arrays.copyOfRange(value, n - (int) range.a(), n));
    }

    /** Values are compared byte for byte, except metadata documents, which icechunk may serialize differently. */
    private static void assertValue(String what, Optional<byte[]> expected, Optional<byte[]> actual) {
        assertEquals(expected.isPresent(), actual.isPresent(), "presence of " + what);
        if (expected.isEmpty()) {
            return;
        }
        if (what.endsWith("zarr.json")) {
            assertEquals(
                    JsonReader.parse(new String(expected.get(), UTF_8)),
                    JsonReader.parse(new String(actual.get(), UTF_8)),
                    what);
        } else {
            assertArrayEquals(expected.get(), actual.get(), what);
        }
    }

    private static boolean sameContents(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : a.entrySet()) {
            if (!Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static SortedMap<String, byte[]> copy(SortedMap<String, byte[]> map) {
        return new TreeMap<>(map);
    }

    private static List<String> sorted(List<String> keys) {
        List<String> copy = new ArrayList<>(keys);
        copy.sort(null);
        return copy;
    }

    private static String metadataKey(String node) {
        return node.isEmpty() ? "zarr.json" : node + "/zarr.json";
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static byte[] group(int attributes) {
        return ("{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{\"n\":" + attributes + "}}")
                .getBytes(UTF_8);
    }

    private static byte[] array(long[] shape, long[] chunks) {
        return ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + json(shape) + ",\"data_type\":\"uint8\","
                        + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + json(chunks)
                        + "}},\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                        + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}")
                .getBytes(UTF_8);
    }

    private static String json(long[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            out.append(i == 0 ? "" : ",").append(values[i]);
        }
        return out.append(']').toString();
    }
}
