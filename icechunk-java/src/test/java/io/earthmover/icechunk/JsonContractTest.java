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
                        + "\"max_compressed_manifest_mem_bytes\":536870912,\"max_concurrent_manifest_fetches\":500,"
                        + "\"dry_run\":true}",
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
}
