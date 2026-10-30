package io.earthmover.icechunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Typed repository configuration, and saving it in the repository. */
class RepositoryConfigTest {
    @Test
    void typedSettingsReachTheRepository() {
        RepositoryConfig config = RepositoryConfig.builder()
                .inlineChunkThresholdBytes(77)
                .getPartialValuesConcurrency(3)
                .compressionLevel(9)
                .cacheChunkRefs(1234)
                .storageRetries(4, 50, 5000)
                .build();
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(
                        storage, RepositoryOptions.builder().config(config).build())) {
            String json = repo.configJson();
            assertTrue(json.contains("\"inline_chunk_threshold_bytes\":77"), json);
            assertTrue(json.contains("\"get_partial_values_concurrency\":3"), json);
            assertTrue(json.contains("\"level\":9"), json);
            assertTrue(json.contains("\"num_chunk_refs\":1234"), json);
            assertTrue(json.contains("\"max_tries\":4"), json);
        }
    }

    @Test
    void savedConfigIsUsedByLaterOpens(@TempDir Path dir) {
        RepositoryConfig config = RepositoryConfig.builder()
                .inlineChunkThresholdBytes(99)
                .virtualChunkContainer(VirtualChunkContainer.localFilesystem("file:///data/", dir))
                .virtualChunkContainer(VirtualChunkContainer.s3("s3://bucket/")
                        .region("us-east-1")
                        .build())
                .build();
        try (Storage storage = Storage.localFilesystem(dir.resolve("repo"))) {
            try (Repository repo = Repository.create(
                    storage, RepositoryOptions.builder().config(config).build())) {
                repo.saveConfig();
            }
            try (Repository repo = Repository.open(storage)) {
                String json = repo.configJson();
                assertTrue(json.contains("\"inline_chunk_threshold_bytes\":99"), json);
                assertTrue(json.contains("\"url_prefix\":\"file:///data/\""), json);
                assertTrue(json.contains("\"url_prefix\":\"s3://bucket/\""), json);
            }
            // Without saveConfig, a config passed at open is not persisted.
            try (Repository repo = Repository.open(
                    storage,
                    RepositoryOptions.builder()
                            .config(RepositoryConfig.builder()
                                    .inlineChunkThresholdBytes(5)
                                    .build())
                            .build())) {
                assertTrue(repo.configJson().contains("\"inline_chunk_threshold_bytes\":5"));
            }
            try (Repository repo = Repository.open(storage)) {
                assertFalse(repo.configJson().contains("\"inline_chunk_threshold_bytes\":5"));
            }
        }
    }

    @Test
    void outOfRangeValuesAreRejected() {
        RepositoryConfig.Builder builder = RepositoryConfig.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.inlineChunkThresholdBytes(70000));
        assertThrows(IllegalArgumentException.class, () -> builder.getPartialValuesConcurrency(0));
        assertThrows(IllegalArgumentException.class, () -> builder.compressionLevel(23));
        assertThrows(IllegalArgumentException.class, () -> builder.cacheSnapshotNodes(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.storageConcurrency(1, 0));
    }

    @Test
    void containersAreKeyedByPrefix() {
        RepositoryConfig config = RepositoryConfig.builder()
                .virtualChunkContainer(VirtualChunkContainer.http("https://a/"))
                .virtualChunkContainer(
                        VirtualChunkContainer.http("https://a/", java.util.Collections.singletonMap("timeout", "5s")))
                .build();
        assertEquals(
                "{\"virtual_chunk_containers\":{\"https://a/\":{\"url_prefix\":\"https://a/\",\"store\":{\"http\":"
                        + "{\"timeout\":\"5s\"}}}}}",
                config.toJson());
        assertEquals(
                "{\"url_prefix\":\"s3://minio/\",\"store\":{\"s3_compatible\":{\"endpoint_url\":\"http://localhost:9000\","
                        + "\"anonymous\":false,\"allow_http\":true,\"force_path_style\":true,\"requester_pays\":false}}}",
                VirtualChunkContainer.s3("s3://minio/")
                        .endpointUrl("http://localhost:9000")
                        .allowHttp(true)
                        .forcePathStyle(true)
                        .build()
                        .toJson()
                        .toString());
    }
}
