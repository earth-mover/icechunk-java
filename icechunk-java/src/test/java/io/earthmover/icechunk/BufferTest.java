package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Writes from every kind of {@link ByteBuffer}: heap, sliced, read-only, and direct above and below the size at which
 * direct buffers are read in place.
 */
class BufferTest {
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
    }

    @AfterEach
    void close() {
        session.close();
        repo.close();
        storage.close();
    }

    private void createArray(String name, int length) {
        store.set(
                name + "/zarr.json",
                ByteBuffer.wrap(("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[" + length
                                + "],\"data_type\":\"uint8\",\"chunk_grid\":{\"name\":\"regular\","
                                + "\"configuration\":{\"chunk_shape\":[" + length + "]}},\"chunk_key_encoding\":"
                                + "{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},\"fill_value\":0,"
                                + "\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}")
                        .getBytes(UTF_8)));
    }

    @Test
    void largeDirectBuffersAreWrittenFromTheirPosition() {
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        createArray("big", big.length);
        ByteBuffer direct = ByteBuffer.allocateDirect(big.length + 10);
        direct.position(10);
        direct.put(big);
        direct.position(10);
        store.set("big/c/0", direct);
        assertEquals(10, direct.position(), "the caller's position is unchanged");
        assertArrayEquals(big, store.get("big/c/0").get());
    }

    @Test
    void smallAndHeapBuffers() {
        createArray("small", 4);

        ByteBuffer smallDirect = ByteBuffer.allocateDirect(4).put(new byte[] {4, 3, 2, 1});
        smallDirect.flip();
        store.set("small/c/0", smallDirect);
        assertArrayEquals(new byte[] {4, 3, 2, 1}, store.get("small/c/0").get());

        ByteBuffer heapSlice =
                ByteBuffer.wrap(new byte[] {9, 9, 1, 2, 3, 4, 9}, 2, 4).slice();
        store.set("small/c/0", heapSlice);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, store.get("small/c/0").get());

        store.set("small/c/0", ByteBuffer.wrap(new byte[] {5, 6, 7, 8}).asReadOnlyBuffer());
        assertArrayEquals(new byte[] {5, 6, 7, 8}, store.get("small/c/0").get());
    }
}
