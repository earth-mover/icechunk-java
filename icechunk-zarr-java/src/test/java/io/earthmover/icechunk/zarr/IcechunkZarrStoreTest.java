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
import io.earthmover.icechunk.ByteRange;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
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
            assertTrue(store.exists(new String[] {"a", "b", "zarr.json"}));
            assertFalse(store.exists(new String[] {"a", "b", "c", "9"}));
            assertNull(store.get(new String[] {"a", "b", "c", "9"}));
            assertEquals(-1, store.getSize(new String[] {"a", "b", "c", "9"}));
            assertEquals(2, store.getSize(new String[] {"a", "b", "c", "1"}));
            assertEquals(3, store.get(new String[] {"a/b/c/1"}, 0, 1).get());
            assertEquals(4, store.get(new String[] {"a", "b", "c", "1"}, -1).get());
        }
    }

    @Test
    void keysAndRanges() {
        assertEquals("a/b/zarr.json", IcechunkZarrStore.key(new String[] {"/a/", "b", "", "zarr.json"}));
        assertEquals("", IcechunkZarrStore.key(new String[] {}));
        assertEquals(ByteRange.all(), IcechunkZarrStore.range(0, -1));
        assertEquals(ByteRange.from(5), IcechunkZarrStore.range(5, -1));
        assertEquals(ByteRange.suffix(16), IcechunkZarrStore.range(-16, -1));
        assertEquals(ByteRange.of(2, 7), IcechunkZarrStore.range(2, 7));
    }

    private static List<String> sorted(Stream<String> keys) {
        return keys.sorted().collect(Collectors.toList());
    }
}
