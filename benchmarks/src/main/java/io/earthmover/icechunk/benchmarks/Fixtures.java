package io.earthmover.icechunk.benchmarks;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Store;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Repository contents shared by the benchmarks: one 1-d uint8 array whose chunks are keys {@code a/c/<i>}. */
final class Fixtures {
    private Fixtures() {}

    static String key(int chunk) {
        return "a/c/" + chunk;
    }

    /** Write the root group and the array's metadata into an open writable store. */
    static void writeMetadata(Store store, int chunkBytes, int chunks) {
        store.set(
                "zarr.json",
                ByteBuffer.wrap("{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}".getBytes(UTF_8)));
        store.set(
                "a/zarr.json",
                ByteBuffer.wrap(("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[" + (long) chunkBytes * chunks
                                + "],\"data_type\":\"uint8\",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":"
                                + "{\"chunk_shape\":[" + chunkBytes
                                + "]}},\"chunk_key_encoding\":{\"name\":\"default\","
                                + "\"configuration\":{\"separator\":\"/\"}},\"fill_value\":0,\"codecs\":"
                                + "[{\"name\":\"bytes\"}],\"attributes\":{}}")
                        .getBytes(UTF_8)));
    }

    /** Commit the array with every chunk set to {@code payload}, from a heap array or a direct buffer. */
    static void writeArray(Repository repo, int chunks, byte[] payload, boolean direct) {
        ByteBuffer directPayload = direct ? directCopy(payload) : null;
        try (Session session = repo.writableSession("main")) {
            Store store = session.store();
            writeMetadata(store, payload.length, chunks);
            for (int i = 0; i < chunks; i++) {
                if (direct) {
                    store.set(key(i), directPayload.duplicate());
                } else {
                    store.set(key(i), ByteBuffer.wrap(payload));
                }
            }
            session.commit("write");
        }
    }

    static ByteBuffer directCopy(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(payload.length).put(payload);
        buffer.flip();
        return buffer;
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
