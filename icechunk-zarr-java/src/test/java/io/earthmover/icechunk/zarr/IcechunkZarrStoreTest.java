package io.earthmover.icechunk.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.DataType;
import dev.zarr.zarrjava.v3.Group;
import dev.zarr.zarrjava.v3.codec.CodecBuilder;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IcechunkZarrStoreTest {
    private Storage storage;
    private Repository repo;

    @BeforeEach
    void open() {
        storage = Storage.inMemory();
        repo = Repository.create(storage);
    }

    @AfterEach
    void close() {
        repo.close();
        storage.close();
    }

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of("bytes", (Function<CodecBuilder, CodecBuilder>) c -> c.withBytes()),
                Arguments.of("zstd", (Function<CodecBuilder, CodecBuilder>)
                        c -> c.withBytes().withZstd()),
                Arguments.of("gzip", (Function<CodecBuilder, CodecBuilder>)
                        c -> c.withBytes().withGzip()),
                Arguments.of("blosc", (Function<CodecBuilder, CodecBuilder>)
                        c -> c.withBytes().withBlosc()),
                Arguments.of("sharding", (Function<CodecBuilder, CodecBuilder>) c -> c.withSharding(
                        new int[] {2, 3}, inner -> inner.withBytes().withZstd())));
    }

    /** Write with zarr-java, commit, and read the committed snapshot back through a new read-only session. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("codecs")
    void roundTrip(String name, Function<CodecBuilder, CodecBuilder> codecs) throws Exception {
        int[] values = new int[8 * 9];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 7 - 100;
        }
        ucar.ma2.Array data = ucar.ma2.Array.factory(ucar.ma2.DataType.INT, new int[] {8, 9}, values);

        try (Session session = repo.writableSession("main")) {
            IcechunkZarrStore store = new IcechunkZarrStore(session);
            Group.create(store.resolve());
            Array array = Array.create(
                    store.resolve("grid"),
                    Array.metadataBuilder()
                            .withShape(8, 9)
                            .withDataType(DataType.INT32)
                            .withChunkShape(4, 6)
                            .withFillValue(0)
                            .withCodecs(codecs)
                            .build());
            array.write(data);
            session.commit("write " + name);
        }

        try (Session session = repo.readonlySession(Version.branch("main"))) {
            Array array = Array.open(new IcechunkZarrStore(session).resolve("grid"));
            assertArrayEquals(values, (int[]) array.read().get1DJavaArray(ucar.ma2.DataType.INT));
            ucar.ma2.Array corner = array.read(new long[] {6, 7}, new long[] {2, 2});
            assertArrayEquals(
                    new int[] {values[6 * 9 + 7], values[6 * 9 + 8], values[7 * 9 + 7], values[7 * 9 + 8]},
                    (int[]) corner.get1DJavaArray(ucar.ma2.DataType.INT));
        }
    }

    @Test
    void listingAndMissingKeys() throws Exception {
        try (Session session = repo.writableSession("main")) {
            IcechunkZarrStore store = new IcechunkZarrStore(session);
            Group.create(store.resolve());
            Group.create(store.resolve("a"));
            Array.create(
                            store.resolve("a", "b"),
                            Array.metadataBuilder()
                                    .withShape(4)
                                    .withDataType(DataType.UINT8)
                                    .withChunkShape(2)
                                    .withFillValue(0)
                                    .build())
                    .write(ucar.ma2.Array.factory(ucar.ma2.DataType.UBYTE, new int[] {4}, new byte[] {1, 2, 3, 4}));

            assertEquals(List.of("a", "zarr.json"), sorted(store.listChildren()));
            assertEquals(
                    List.of("b/c/0", "b/c/1", "b/zarr.json", "zarr.json"),
                    sorted(store.list(new String[] {"a"}).map(k -> String.join("/", k))));
            assertEquals(
                    List.of("0", "1"),
                    sorted(store.list(new String[] {"a", "b", "c"}).map(k -> String.join("/", k))),
                    "a prefix below an array lists that array's keys under it");
            assertEquals(0, store.list(new String[] {"nothing", "here"}).count());
            assertTrue(store.exists(new String[] {"a", "b", "zarr.json"}));
            assertFalse(store.exists(new String[] {"a", "b", "c", "9"}));
            assertNull(store.get(new String[] {"a", "b", "c", "9"}));
            assertEquals(-1, store.getSize(new String[] {"a", "b", "c", "9"}));
            assertEquals(2, store.getSize(new String[] {"a", "b", "c", "1"}));
            assertEquals(3, store.get(new String[] {"a/b/c/1"}, 0, 1).get(0));
            assertEquals(4, store.get(new String[] {"a", "b", "c", "1"}, -1).get(0));
        }
    }

    @Test
    void keys() {
        assertEquals("a/b/zarr.json", IcechunkZarrStore.key(new String[] {"/a/", "b", "", "zarr.json"}));
        assertEquals("", IcechunkZarrStore.key(new String[] {}));
    }

    /** zarr-java's range convention, as its FilesystemStore implements it. */
    @Test
    void ranges() throws Exception {
        try (Session session = repo.writableSession("main")) {
            IcechunkZarrStore store = new IcechunkZarrStore(session);
            Group.create(store.resolve());
            Array.create(
                            store.resolve("x"),
                            Array.metadataBuilder()
                                    .withShape(8)
                                    .withDataType(DataType.UINT8)
                                    .withChunkShape(8)
                                    .withFillValue(0)
                                    .build())
                    .write(ucar.ma2.Array.factory(
                            ucar.ma2.DataType.UBYTE, new int[] {8}, new byte[] {0, 1, 2, 3, 4, 5, 6, 7}));
            String[] chunk = {"x", "c", "0"};
            assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}, bytes(store.get(chunk)));
            assertArrayEquals(new byte[] {5, 6, 7}, bytes(store.get(chunk, 5)));
            assertArrayEquals(new byte[] {2, 3}, bytes(store.get(chunk, 2, 4)));
            assertArrayEquals(new byte[] {6, 7}, bytes(store.get(chunk, -2)));
            assertArrayEquals(new byte[] {4, 5}, bytes(store.get(chunk, -4, 6)));
            assertArrayEquals(new byte[] {}, bytes(store.get(chunk, -2, 3)), "an end before the start reads nothing");
            assertNull(store.get(new String[] {"x", "c", "1"}, -4, 6));
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    private static List<String> sorted(Stream<String> keys) {
        return keys.sorted().collect(Collectors.toList());
    }
}
