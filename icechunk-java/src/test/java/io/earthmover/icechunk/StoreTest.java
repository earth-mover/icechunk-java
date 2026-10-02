package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreTest {
    static final byte[] ARRAY = ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[4],\"data_type\":\"uint8\","
                    + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[4]}},"
                    + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                    + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}")
            .getBytes(UTF_8);
    static final byte[] CHUNK = {10, 11, 12, 13};

    private Storage storage;
    private Repository repo;
    private Session session;
    private Store store;

    @BeforeEach
    void open() {
        storage = Storage.inMemory();
        repo = Repository.create(storage);
        session = repo.writableSession("main");
        store = session.store();
        store.set("zarr.json", ByteBuffer.wrap(GROUP));
        store.set("data/zarr.json", ByteBuffer.wrap(ARRAY));
        store.set("data/c/0", ByteBuffer.wrap(CHUNK));
    }

    @AfterEach
    void close() {
        session.close();
        repo.close();
        storage.close();
    }

    @Test
    void sameStoreEachCall() {
        assertTrue(store == session.store());
    }

    @Test
    void getWholeAndRanges() {
        assertArrayEquals(CHUNK, store.get("data/c/0").get());
        assertArrayEquals(
                new byte[] {11, 12}, store.get("data/c/0", ByteRange.of(1, 3)).get());
        assertArrayEquals(
                new byte[] {12, 13}, store.get("data/c/0", ByteRange.from(2)).get());
        assertArrayEquals(
                new byte[] {13}, store.get("data/c/0", ByteRange.suffix(1)).get());
    }

    @Test
    void missingKeysAreEmpty() {
        assertEquals(Optional.empty(), store.get("data/c/1"));
        assertEquals(Optional.empty(), store.get("nope/zarr.json"));
        assertFalse(store.exists("data/c/1"));
        assertEquals(OptionalLong.empty(), store.getSize("data/c/1"));
    }

    @Test
    void sizes() {
        assertEquals(OptionalLong.of(4), store.getSize("data/c/0"));
        assertEquals(OptionalLong.of(ARRAY.length), store.getSize("data/zarr.json"));
    }

    @Test
    void getPartialValues() {
        List<Optional<byte[]>> values = store.getPartialValues(
                Arrays.asList("data/c/0", "data/c/9", "zarr.json"),
                Arrays.asList(ByteRange.suffix(2), ByteRange.all(), ByteRange.all()));
        assertEquals(3, values.size());
        assertArrayEquals(new byte[] {12, 13}, values.get(0).get());
        assertEquals(Optional.empty(), values.get(1));
        assertArrayEquals(GROUP, values.get(2).get());
    }

    @Test
    void listing() {
        assertEquals(Arrays.asList("data/c/0", "data/zarr.json", "zarr.json"), sorted(store.list()));
        assertEquals(Arrays.asList("data/c/0", "data/zarr.json"), sorted(store.listPrefix("data")));
        assertEquals(Arrays.asList("data", "zarr.json"), sorted(store.listDir("")));
        assertEquals(Arrays.asList("c", "zarr.json"), sorted(store.listDir("data")));
        assertFalse(store.isEmpty("data"));
        assertTrue(store.isEmpty("nothing"));
    }

    @Test
    void deleteAndDeleteDir() {
        store.delete("data/c/0");
        assertFalse(store.exists("data/c/0"));
        store.deleteDir("data");
        assertFalse(store.exists("data/zarr.json"));
        assertTrue(store.exists("zarr.json"));
    }

    @Test
    void setIfNotExistsKeepsTheOldValue() {
        store.setIfNotExists("data/c/0", ByteBuffer.wrap(new byte[] {1, 1, 1, 1}));
        assertArrayEquals(CHUNK, store.get("data/c/0").get());
    }

    @Test
    void discardChanges() {
        session.discardChanges();
        assertFalse(session.hasUncommittedChanges());
        assertFalse(store.exists("zarr.json"));
    }

    @Test
    void invalidRangesAreRejectedInJava() {
        assertThrows(IllegalArgumentException.class, () -> ByteRange.of(3, 1));
        assertThrows(IllegalArgumentException.class, () -> ByteRange.suffix(-1));
    }

    private static List<String> sorted(List<String> keys) {
        String[] array = keys.toArray(new String[0]);
        Arrays.sort(array);
        return Arrays.asList(array);
    }
}
