package io.earthmover.icechunk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * The documents the builders send to the native layer. The same strings are parsed by the tests in
 * {@code native/src/spec.rs}; a change on one side must be made on the other.
 */
class JsonContractTest {
    @Test
    void s3() {
        String json = S3Options.builder("b")
                .prefix("p")
                .region("us-east-1")
                .endpointUrl("http://localhost:9000")
                .allowHttp(true)
                .forcePathStyle(true)
                .credentials(S3Credentials.of("a", "s"))
                .build()
                .toJson()
                .toString();
        assertEquals(
                "{\"type\":\"s3\",\"bucket\":\"b\",\"prefix\":\"p\",\"options\":{\"region\":\"us-east-1\","
                        + "\"endpoint_url\":\"http://localhost:9000\",\"allow_http\":true,\"force_path_style\":true,"
                        + "\"requester_pays\":false},\"credentials\":{\"type\":\"static\",\"access_key_id\":\"a\","
                        + "\"secret_access_key\":\"s\"}}",
                json);
    }

    @Test
    void repositoryOptions() {
        String json = RepositoryOptions.builder()
                .configJson("{\"inline_chunk_threshold_bytes\":12}")
                .authorizeVirtualChunkAccess("s3://bucket/", Credentials.s3(S3Credentials.anonymous()))
                .authorizeVirtualChunkAccess("file:///data/", Credentials.localFilesystem())
                .checkCleanRoot(false)
                .build()
                .toJson();
        assertEquals(
                "{\"config\":{\"inline_chunk_threshold_bytes\":12},\"virtual_chunk_credentials\":{\"s3://bucket/\":"
                        + "{\"type\":\"s3\",\"credentials\":{\"type\":\"anonymous\"}},\"file:///data/\":"
                        + "{\"type\":\"local_filesystem\"}},\"check_clean_root\":false}",
                json);
    }

    /** The same strings appear in the {@code versions} test in {@code spec.rs}. */
    @Test
    void versions() {
        assertEquals(
                "{\"type\":\"branch\",\"name\":\"main\"}",
                Version.branch("main").toJson());
        assertEquals("{\"type\":\"tag\",\"name\":\"v1\"}", Version.tag("v1").toJson());
        assertEquals(
                "{\"type\":\"snapshot_id\",\"id\":\"1CECHNKREP0F1RSTCMT0\"}",
                Version.snapshot(SnapshotId.of("1CECHNKREP0F1RSTCMT0")).toJson());
        assertEquals(
                "{\"type\":\"as_of\",\"branch\":\"main\",\"at\":\"2026-01-02T03:04:05.000006Z\"}",
                Version.asOf("main", Instant.parse("2026-01-02T03:04:05.000006Z"))
                        .toJson());
    }

    @Test
    void commitOptions() {
        assertEquals(
                "{\"metadata\":{\"author\":\"ian\",\"n\":1},\"allow_empty\":true}",
                CommitOptions.builder()
                        .metadata("author", "ian")
                        .metadata("n", 1)
                        .allowEmpty(true)
                        .build()
                        .toJson());
        assertEquals("{\"allow_empty\":false}", CommitOptions.defaults().toJson());
    }

    /** The same strings appear in the {@code expire_options} and {@code gc_options} tests in {@code spec.rs}. */
    @Test
    void expireOptions() {
        assertEquals(
                "{\"older_than\":\"2026-01-02T03:04:05Z\",\"delete_expired_branches\":true,"
                        + "\"delete_expired_tags\":false}",
                ExpireOptions.builder()
                        .deleteExpiredBranches(true)
                        .build()
                        .toJson(Instant.parse("2026-01-02T03:04:05Z")));
    }

    @Test
    void gcOptions() {
        assertEquals(
                "{\"extra_roots\":[\"1CECHNKREP0F1RSTCMT0\"],\"delete_chunks_older_than\":\"2026-01-02T03:04:05Z\","
                        + "\"delete_snapshots_older_than\":\"2026-01-01T00:00:00Z\",\"max_snapshots_in_memory\":50,"
                        + "\"max_compressed_manifest_mem_bytes\":536870912,\"max_decoded_manifest_mem_bytes\":4294967296,"
                        + "\"max_concurrent_manifest_fetches\":500,\"max_concurrent_deletes\":10,"
                        + "\"max_consecutive_delete_failures\":50,\"dry_run\":true}",
                GcOptions.builder()
                        .extraRoots(Collections.singleton(SnapshotId.of("1CECHNKREP0F1RSTCMT0")))
                        .deleteChunksOlderThan(Instant.parse("2026-01-02T03:04:05Z"))
                        .deleteSnapshotsOlderThan(Instant.parse("2026-01-01T00:00:00Z"))
                        .dryRun(true)
                        .build()
                        .toJson());
    }

    @Test
    void escaping() {
        assertEquals(
                "{\"k\":\"a\\\"b\\\\c\\n\\u0001\"}",
                Json.object().put("k", "a\"b\\c\n\u0001").toString());
    }

    /** The same string appears in the {@code typed_repository_config} test in {@code spec.rs}. */
    @Test
    void typedRepositoryConfig() {
        RepositoryConfig config = RepositoryConfig.builder()
                .inlineChunkThresholdBytes(12)
                .getPartialValuesConcurrency(4)
                .maxConcurrentRequests(64)
                .maxConcurrentDecodes(2)
                .compressionLevel(5)
                .cacheSnapshotNodes(1)
                .cacheBytesChunks(2)
                .storageConcurrency(4, 1048576)
                .storageRetries(3, 10, 1000)
                .unsafeUseConditionalUpdate(false)
                .storageClass("STANDARD_IA")
                .manifestPreload(100, 10)
                .maxConcurrentManifestFetchesDuringCommit(8)
                .virtualChunkContainer(VirtualChunkContainer.s3("s3://b/")
                        .region("us-east-1")
                        .anonymous(true)
                        .build())
                .virtualChunkContainer(VirtualChunkContainer.inMemory("memory://"))
                .virtualChunkContainer(VirtualChunkContainer.http("https://e/"))
                .build();
        assertEquals(
                "{\"inline_chunk_threshold_bytes\":12,\"get_partial_values_concurrency\":4,\"max_concurrent_requests\":64,"
                        + "\"max_concurrent_decodes\":2,\"compression\":{\"level\":5},\"caching\":{\"num_snapshot_nodes\":1,"
                        + "\"num_bytes_chunks\":2},\"storage\":{\"concurrency\":{\"max_concurrent_requests_for_object\":4,"
                        + "\"ideal_concurrent_request_size\":1048576},\"retries\":{\"max_tries\":3,\"initial_backoff_ms\":10,"
                        + "\"max_backoff_ms\":1000},\"unsafe_use_conditional_update\":false,\"storage_class\":\"STANDARD_IA\"},"
                        + "\"virtual_chunk_containers\":{\"s3://b/\":{\"url_prefix\":\"s3://b/\",\"store\":{\"s3\":{\"region\":\"us-east-1\","
                        + "\"anonymous\":true,\"allow_http\":false,\"force_path_style\":false,\"requester_pays\":false}}},"
                        + "\"memory://\":{\"url_prefix\":\"memory://\",\"store\":\"in_memory\"},\"https://e/\":{\"url_prefix\":\"https://e/\","
                        + "\"store\":{\"http\":{}}}},\"manifest\":{\"preload\":{\"max_total_refs\":100,\"max_arrays_to_scan\":10},"
                        + "\"max_concurrent_manifest_fetches_during_commit\":8}}",
                config.toJson());
        assertEquals("{}", RepositoryConfig.builder().build().toJson());
        assertEquals(
                "{\"config\":{\"compression\":{\"level\":5}},\"virtual_chunk_credentials\":{},\"check_clean_root\":true}",
                RepositoryOptions.builder()
                        .config(RepositoryConfig.builder().compressionLevel(5).build())
                        .build()
                        .toJson());
    }
}
