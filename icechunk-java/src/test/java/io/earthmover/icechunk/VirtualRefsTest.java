package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Writing virtual chunk references and reading them back through a local filesystem container. */
class VirtualRefsTest {
    /** Eight uint8 values in two chunks of four. */
    private static final byte[] ARRAY =
            ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[8],\"data_type\":\"uint8\","
                            + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[4]}},"
                            + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                            + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}")
                    .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private Path data;
    private String prefix;

    @BeforeEach
    void writeSourceFile() throws IOException {
        Path sources = Files.createDirectories(dir.resolve("sources"));
        data = sources.resolve("data.bin");
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        Files.write(data, bytes);
        prefix = sources.toUri().toString(); // file:///.../sources/
    }

    private RepositoryOptions options() {
        return RepositoryOptions.builder()
                .config(RepositoryConfig.builder()
                        .virtualChunkContainer(VirtualChunkContainer.localFilesystem(prefix, data.getParent()))
                        .build())
                .authorizeVirtualChunkAccess(prefix, Credentials.localFilesystem())
                .build();
    }

    private String location() {
        return data.toUri().toString();
    }

    @Test
    void virtualChunksAreReadFromTheSourceFile() {
        try (Storage storage = Storage.localFilesystem(dir.resolve("repo"))) {
            try (Repository repo = Repository.create(storage, options())) {
                repo.saveConfig();
                try (Session session = repo.writableSession("main")) {
                    Store store = session.store();
                    store.set("zarr.json", ByteBuffer.wrap(GROUP));
                    store.set("a/zarr.json", ByteBuffer.wrap(ARRAY));
                    store.setVirtualRef("a/c/0", VirtualChunkRef.of(location(), 4, 4));
                    Map<List<Long>, VirtualChunkRef> refs = new LinkedHashMap<>();
                    refs.put(Collections.singletonList(1L), VirtualChunkRef.of(location(), 8, 4));
                    assertEquals(Collections.emptyList(), store.setVirtualRefs("/a", refs, true));
                    assertArrayEquals(
                            new byte[] {4, 5, 6, 7}, store.get("a/c/0").get());
                    session.commit("virtual chunks");
                }
            }
            // A later reader, with the saved container and access granted.
            try (Repository repo = Repository.open(
                            storage,
                            RepositoryOptions.builder()
                                    .authorizeVirtualChunkAccess(prefix, Credentials.localFilesystem())
                                    .build());
                    Session session = repo.readonlySession(Version.branch("main"))) {
                assertArrayEquals(
                        new byte[] {4, 5, 6, 7}, session.store().get("a/c/0").get());
                assertArrayEquals(
                        new byte[] {8, 9, 10, 11}, session.store().get("a/c/1").get());
                assertArrayEquals(
                        new byte[] {9, 10},
                        session.store().get("a/c/1", ByteRange.of(1, 3)).get());
            }
        }
    }

    @Test
    void locationsOutsideEveryContainerAreRejected() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage, options());
                Session session = repo.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", ByteBuffer.wrap(GROUP));
            store.set("a/zarr.json", ByteBuffer.wrap(ARRAY));
            VirtualChunkRef elsewhere = VirtualChunkRef.of("s3://other-bucket/file.nc", 0, 4);
            assertThrows(IcechunkException.class, () -> store.setVirtualRef("a/c/0", elsewhere));
            // Without validation the reference is written, and fails only when read.
            store.setVirtualRef("a/c/0", elsewhere, false);

            Map<List<Long>, VirtualChunkRef> refs = new LinkedHashMap<>();
            refs.put(Collections.singletonList(0L), elsewhere);
            refs.put(Collections.singletonList(1L), VirtualChunkRef.of(location(), 0, 4));
            List<List<Long>> failed = store.setVirtualRefs("/a", refs, true);
            assertEquals(Collections.singletonList(Collections.singletonList(0L)), failed);
            assertArrayEquals(new byte[] {0, 1, 2, 3}, store.get("a/c/1").get());

            assertThrows(
                    IllegalArgumentException.class, () -> store.setVirtualRef("a/c/0", VirtualChunkRef.of("x", 0, 1)));
            assertThrows(IcechunkException.class, () -> store.setVirtualRefs("/missing", refs, true));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.setVirtualRefs(
                            "/a",
                            Collections.singletonMap(Arrays.asList(-1L), VirtualChunkRef.of(location(), 0, 4)),
                            true));
        }
    }

    @Test
    void aChecksumCatchesAChangedSourceFile() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage, options());
                Session session = repo.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", ByteBuffer.wrap(GROUP));
            store.set("a/zarr.json", ByteBuffer.wrap(ARRAY));
            // The file was written after this time, so the reference is stale.
            store.setVirtualRef(
                    "a/c/0", VirtualChunkRef.of(location(), 0, 4).withLastModified(Instant.ofEpochSecond(1_000_000)));
            IcechunkException e = assertThrows(IcechunkException.class, () -> store.get("a/c/0"));
            assertTrue(e.getMessage().contains("checksum"), e.getMessage());
            // A checksum from the future is satisfied.
            store.setVirtualRef(
                    "a/c/0",
                    VirtualChunkRef.of(location(), 0, 4).withLastModified(Instant.parse("2100-01-01T00:00:00Z")));
            assertArrayEquals(new byte[] {0, 1, 2, 3}, store.get("a/c/0").get());
        }
    }
}
