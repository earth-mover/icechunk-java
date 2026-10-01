package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static io.earthmover.icechunk.StoreTest.ARRAY;
import static io.earthmover.icechunk.StoreTest.CHUNK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        store.set("zarr.json", GROUP);
        store.set("data/zarr.json", ARRAY);
        store.set("data/c/0", CHUNK);
    }

    @AfterEach
    void close() {
        session.close();
        repo.close();
        storage.close();
    }

    static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    @Test
    void buffersAreReadOnlyViewsOfTheValue() {
        ByteBuffer buffer = store.getBuffer("data/c/0").orElseThrow();
        assertTrue(buffer.isDirect());
        assertTrue(buffer.isReadOnly());
        assertArrayEquals(CHUNK, bytes(buffer));
        assertThrows(ReadOnlyBufferException.class, () -> buffer.put(0, (byte) 1));
        assertArrayEquals(
                new byte[] {12, 13},
                bytes(store.getBuffer("data/c/0", ByteRange.suffix(2)).orElseThrow()));
        assertEquals(Optional.empty(), store.getBuffer("data/c/1"));
    }

    @Test
    void getManyBuffers() {
        List<Optional<ByteBuffer>> values = store.getManyBuffers(Arrays.asList("data/c/0", "data/c/9", "zarr.json"));
        assertArrayEquals(CHUNK, bytes(values.get(0).orElseThrow()));
        assertEquals(Optional.empty(), values.get(1));
        assertArrayEquals(GROUP, bytes(values.get(2).orElseThrow()));
    }

    /**
     * Native memory stays valid as long as any view of the buffer is reachable, and is released after the last view is
     * collected.
     */
    @Test
    void nativeMemoryFollowsReachability() throws Exception {
        byte[] big = new byte[1 << 20];
        Arrays.fill(big, (byte) 7);
        store.set("big/zarr.json", bigArray(big.length));
        store.set("big/c/0", big);
        // Buffers from earlier tests in this JVM are unreachable; let them go first.
        awaitOutstanding(0);
        long before = Native.bufferOutstanding();

        ByteBuffer slice = slice(store.getBuffer("big/c/0").orElseThrow());
        assertEquals(before + big.length, Native.bufferOutstanding());
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(before + big.length, Native.bufferOutstanding(), "a live slice keeps the memory");
        assertEquals(7, slice.get(1000));

        slice = null;
        awaitOutstanding(before);
        assertEquals(before, Native.bufferOutstanding(), "memory is released once nothing refers to it");
    }

    private static void awaitOutstanding(long target) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Native.bufferOutstanding() > target && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
    }

    private static ByteBuffer slice(ByteBuffer buffer) {
        buffer.position(100);
        return buffer.slice();
    }

    private static byte[] bigArray(int length) {
        return ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[" + length + "],\"data_type\":\"uint8\","
                        + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[" + length + "]}},"
                        + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                        + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}")
                .getBytes(UTF_8);
    }

    @Test
    void writesFromEveryKindOfBuffer() {
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        store.set("big/zarr.json", bigArray(big.length));

        ByteBuffer direct = ByteBuffer.allocateDirect(big.length + 10);
        direct.position(10);
        direct.put(big);
        direct.position(10);
        store.set("big/c/0", direct);
        assertEquals(10, direct.position(), "the caller's position is unchanged");
        assertArrayEquals(big, store.get("big/c/0").orElseThrow());

        ByteBuffer smallDirect = ByteBuffer.allocateDirect(4).put(new byte[] {4, 3, 2, 1});
        smallDirect.flip();
        store.set("data/c/0", smallDirect);
        assertArrayEquals(new byte[] {4, 3, 2, 1}, store.get("data/c/0").orElseThrow());

        ByteBuffer heapSlice =
                ByteBuffer.wrap(new byte[] {9, 9, 1, 2, 3, 4, 9}, 2, 4).slice();
        store.set("data/c/0", heapSlice);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, store.get("data/c/0").orElseThrow());

        store.set("data/c/0", ByteBuffer.wrap(new byte[] {5, 6, 7, 8}).asReadOnlyBuffer());
        assertArrayEquals(new byte[] {5, 6, 7, 8}, store.get("data/c/0").orElseThrow());
    }
}
