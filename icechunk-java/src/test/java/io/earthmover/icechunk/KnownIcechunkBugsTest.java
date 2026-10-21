package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static io.earthmover.icechunk.StoreTest.ARRAY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * Bugs in icechunk itself, each pinned by a test that asserts the buggy behaviour. A test here failing after an
 * icechunk update means the bug is fixed: delete the test, and lift the limit it names in
 * {@code property-tests/.../StoreStateMachineTest.drawRange}, which keeps the state machine away from the bug.
 */
class KnownIcechunkBugsTest {
    /** icechunk slices a metadata document with an unchecked range, and the slice panics. */
    @Test
    void metadataRangePastTheEndPanics() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage);
                Session session = repo.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", ByteBuffer.wrap(GROUP));
            RuntimeException e =
                    assertThrows(RuntimeException.class, () -> store.get("zarr.json", ByteRange.of(0, 1000)));
            assertTrue(e.getMessage().contains("icechunk native code panicked"), e.getMessage());
        }
    }

    /**
     * icechunk passes an empty range of a native chunk to the object store, which rejects it; the same range of an
     * inline chunk reads as empty.
     */
    @Test
    void emptyRangeOfNativeChunkFails() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(
                        storage,
                        RepositoryOptions.builder()
                                .configJson("{\"inline_chunk_threshold_bytes\":0}")
                                .build());
                Session session = repo.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", ByteBuffer.wrap(GROUP));
            store.set("data/zarr.json", ByteBuffer.wrap(ARRAY));
            store.set("data/c/0", ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
            IcechunkException e =
                    assertThrows(IcechunkException.class, () -> store.get("data/c/0", ByteRange.of(1, 1)));
            assertTrue(e.getMessage().contains("Invalid range"), e.getMessage());
        }
    }
}
