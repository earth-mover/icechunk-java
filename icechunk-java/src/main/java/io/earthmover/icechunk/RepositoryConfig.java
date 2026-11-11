package io.earthmover.icechunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Typed repository configuration, the Java form of icechunk-python's {@code RepositoryConfig}. Settings left unset
 * keep the value stored in the repository, or icechunk's default.
 *
 * <pre>{@code
 * RepositoryConfig config = RepositoryConfig.builder()
 *         .inlineChunkThresholdBytes(512)
 *         .compressionLevel(5)
 *         .storageRetries(5, 100, 10_000)
 *         .virtualChunkContainer(VirtualChunkContainer.s3("s3://source-bucket/").region("us-east-1").build())
 *         .build();
 * Repository repo = Repository.open(storage, RepositoryOptions.builder().config(config).build());
 * repo.saveConfig();  // persist it, for example so other readers see the container
 * }</pre>
 *
 * <p>For settings this class does not cover, pass icechunk's JSON form with {@link
 * RepositoryOptions.Builder#configJson}.
 */
public final class RepositoryConfig {
    private final String json;

    private RepositoryConfig(Builder builder) {
        Json caching = Json.object()
                .put("num_snapshot_nodes", builder.cacheSnapshotNodes)
                .put("num_chunk_refs", builder.cacheChunkRefs)
                .put("num_transaction_changes", builder.cacheTransactionChanges)
                .put("num_bytes_attributes", builder.cacheBytesAttributes)
                .put("num_bytes_chunks", builder.cacheBytesChunks);
        boolean hasCaching = builder.cacheSnapshotNodes != null
                || builder.cacheChunkRefs != null
                || builder.cacheTransactionChanges != null
                || builder.cacheBytesAttributes != null
                || builder.cacheBytesChunks != null;

        Json storage = Json.object();
        if (builder.maxConcurrentRequestsForObject != null || builder.idealConcurrentRequestSize != null) {
            storage.put(
                    "concurrency",
                    Json.object()
                            .put("max_concurrent_requests_for_object", builder.maxConcurrentRequestsForObject)
                            .put("ideal_concurrent_request_size", builder.idealConcurrentRequestSize));
        }
        if (builder.maxTries != null) {
            storage.put(
                    "retries",
                    Json.object()
                            .put("max_tries", builder.maxTries)
                            .put("initial_backoff_ms", builder.initialBackoffMs)
                            .put("max_backoff_ms", builder.maxBackoffMs));
        }
        if (builder.unsafeUseConditionalUpdate != null) {
            storage.put("unsafe_use_conditional_update", builder.unsafeUseConditionalUpdate);
        }
        if (builder.unsafeUseConditionalCreate != null) {
            storage.put("unsafe_use_conditional_create", builder.unsafeUseConditionalCreate);
        }
        storage.put("storage_class", builder.storageClass)
                .put("metadata_storage_class", builder.metadataStorageClass)
                .put("chunks_storage_class", builder.chunksStorageClass);
        boolean hasStorage = builder.maxConcurrentRequestsForObject != null
                || builder.idealConcurrentRequestSize != null
                || builder.maxTries != null
                || builder.unsafeUseConditionalUpdate != null
                || builder.unsafeUseConditionalCreate != null
                || builder.storageClass != null
                || builder.metadataStorageClass != null
                || builder.chunksStorageClass != null;

        Json containers = Json.object();
        for (VirtualChunkContainer container : builder.containers) {
            containers.put(container.urlPrefix(), container.toJson());
        }

        Json manifest = Json.object();
        if (builder.preloadMaxTotalRefs != null || builder.preloadMaxArraysToScan != null) {
            manifest.put(
                    "preload",
                    Json.object()
                            .put("max_total_refs", builder.preloadMaxTotalRefs)
                            .put("max_arrays_to_scan", builder.preloadMaxArraysToScan));
        }
        manifest.put("max_concurrent_manifest_fetches_during_commit", builder.maxConcurrentManifestFetches);
        boolean hasManifest = builder.preloadMaxTotalRefs != null
                || builder.preloadMaxArraysToScan != null
                || builder.maxConcurrentManifestFetches != null;

        Json json = Json.object()
                .put("inline_chunk_threshold_bytes", builder.inlineChunkThresholdBytes)
                .put("get_partial_values_concurrency", builder.getPartialValuesConcurrency)
                .put("max_concurrent_requests", builder.maxConcurrentRequests)
                .put("max_concurrent_decodes", builder.maxConcurrentDecodes);
        if (builder.compressionLevel != null) {
            json.put("compression", Json.object().put("level", builder.compressionLevel));
        }
        if (hasCaching) {
            json.put("caching", caching);
        }
        if (hasStorage) {
            json.put("storage", storage);
        }
        if (!builder.containers.isEmpty()) {
            json.put("virtual_chunk_containers", containers);
        }
        if (hasManifest) {
            json.put("manifest", manifest);
        }
        this.json = json.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** icechunk's JSON form, as {@link RepositoryOptions.Builder#configJson} takes it. */
    public String toJson() {
        return json;
    }

    @Override
    public String toString() {
        return "RepositoryConfig" + json;
    }

    private static Integer u16(String name, int value) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535");
        }
        return value;
    }

    private static Integer positiveU16(String name, int value) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
        return value;
    }

    private static Long nonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static Integer nonNegativeInt(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    public static final class Builder {
        private Integer inlineChunkThresholdBytes;
        private Integer getPartialValuesConcurrency;
        private Integer maxConcurrentRequests;
        private Integer maxConcurrentDecodes;
        private Integer compressionLevel;
        private Long cacheSnapshotNodes;
        private Long cacheChunkRefs;
        private Long cacheTransactionChanges;
        private Long cacheBytesAttributes;
        private Long cacheBytesChunks;
        private Integer maxConcurrentRequestsForObject;
        private Long idealConcurrentRequestSize;
        private Integer maxTries;
        private Integer initialBackoffMs;
        private Integer maxBackoffMs;
        private Boolean unsafeUseConditionalUpdate;
        private Boolean unsafeUseConditionalCreate;
        private String storageClass;
        private String metadataStorageClass;
        private String chunksStorageClass;
        private Integer preloadMaxTotalRefs;
        private Integer preloadMaxArraysToScan;
        private Integer maxConcurrentManifestFetches;
        private final List<VirtualChunkContainer> containers = new ArrayList<>();

        private Builder() {}

        /** Chunks smaller than this many bytes are stored inside the manifest instead of as separate objects. */
        public Builder inlineChunkThresholdBytes(int bytes) {
            this.inlineChunkThresholdBytes = u16("inlineChunkThresholdBytes", bytes);
            return this;
        }

        /** How many keys {@link Store#getPartialValues} fetches at once. */
        public Builder getPartialValuesConcurrency(int concurrency) {
            this.getPartialValuesConcurrency = positiveU16("getPartialValuesConcurrency", concurrency);
            return this;
        }

        /** The most requests to object storage in flight at once, across the repository. */
        public Builder maxConcurrentRequests(int requests) {
            this.maxConcurrentRequests = positiveU16("maxConcurrentRequests", requests);
            return this;
        }

        /** The most metadata files decompressed at once. Defaults to one per core. */
        public Builder maxConcurrentDecodes(int decodes) {
            this.maxConcurrentDecodes = positiveU16("maxConcurrentDecodes", decodes);
            return this;
        }

        /** The zstd level for metadata files icechunk writes (1 to 22; icechunk's default is 3). */
        public Builder compressionLevel(int level) {
            if (level < 1 || level > 22) {
                throw new IllegalArgumentException("compressionLevel must be between 1 and 22");
            }
            this.compressionLevel = level;
            return this;
        }

        /** How many snapshot nodes to keep in the in-memory cache. */
        public Builder cacheSnapshotNodes(long nodes) {
            this.cacheSnapshotNodes = nonNegative("cacheSnapshotNodes", nodes);
            return this;
        }

        /** How many chunk references to keep in the in-memory cache. */
        public Builder cacheChunkRefs(long refs) {
            this.cacheChunkRefs = nonNegative("cacheChunkRefs", refs);
            return this;
        }

        /** How many transaction log changes to keep in the in-memory cache. */
        public Builder cacheTransactionChanges(long changes) {
            this.cacheTransactionChanges = nonNegative("cacheTransactionChanges", changes);
            return this;
        }

        /** How many bytes of attributes to keep in the in-memory cache. */
        public Builder cacheBytesAttributes(long bytes) {
            this.cacheBytesAttributes = nonNegative("cacheBytesAttributes", bytes);
            return this;
        }

        /** How many bytes of chunks to keep in the in-memory cache. */
        public Builder cacheBytesChunks(long bytes) {
            this.cacheBytesChunks = nonNegative("cacheBytesChunks", bytes);
            return this;
        }

        /**
         * Split large objects into concurrent ranged requests: at most {@code maxRequestsPerObject} at once, each of
         * about {@code idealRequestSizeBytes}.
         */
        public Builder storageConcurrency(int maxRequestsPerObject, long idealRequestSizeBytes) {
            this.maxConcurrentRequestsForObject = positiveU16("maxRequestsPerObject", maxRequestsPerObject);
            if (idealRequestSizeBytes < 1) {
                throw new IllegalArgumentException("idealRequestSizeBytes must be positive");
            }
            this.idealConcurrentRequestSize = idealRequestSizeBytes;
            return this;
        }

        /** Retry failed storage requests up to {@code maxTries} times in all, backing off exponentially. */
        public Builder storageRetries(int maxTries, int initialBackoffMs, int maxBackoffMs) {
            this.maxTries = positiveU16("maxTries", maxTries);
            this.initialBackoffMs = nonNegativeInt("initialBackoffMs", initialBackoffMs);
            this.maxBackoffMs = nonNegativeInt("maxBackoffMs", maxBackoffMs);
            return this;
        }

        /**
         * Whether to rely on conditional updates for safe concurrent commits. Turn this off only for storage that does
         * not support them, and only with a single writer.
         */
        public Builder unsafeUseConditionalUpdate(boolean use) {
            this.unsafeUseConditionalUpdate = use;
            return this;
        }

        /** As {@link #unsafeUseConditionalUpdate}, for conditional creates. */
        public Builder unsafeUseConditionalCreate(boolean use) {
            this.unsafeUseConditionalCreate = use;
            return this;
        }

        /** The storage class for every object icechunk writes, such as {@code STANDARD_IA} on S3. */
        public Builder storageClass(String storageClass) {
            this.storageClass = Objects.requireNonNull(storageClass, "storageClass");
            return this;
        }

        /** The storage class for metadata objects, overriding {@link #storageClass}. */
        public Builder metadataStorageClass(String storageClass) {
            this.metadataStorageClass = Objects.requireNonNull(storageClass, "storageClass");
            return this;
        }

        /** The storage class for chunk objects, overriding {@link #storageClass}. */
        public Builder chunksStorageClass(String storageClass) {
            this.chunksStorageClass = Objects.requireNonNull(storageClass, "storageClass");
            return this;
        }

        /**
         * Limits for loading manifests in the background when a session opens: at most {@code maxTotalRefs} chunk
         * references, looking at most at {@code maxArraysToScan} arrays.
         */
        public Builder manifestPreload(int maxTotalRefs, int maxArraysToScan) {
            this.preloadMaxTotalRefs = nonNegativeInt("maxTotalRefs", maxTotalRefs);
            this.preloadMaxArraysToScan = nonNegativeInt("maxArraysToScan", maxArraysToScan);
            return this;
        }

        /** The most manifests a commit fetches at once. */
        public Builder maxConcurrentManifestFetchesDuringCommit(int fetches) {
            this.maxConcurrentManifestFetches = positiveU16("maxConcurrentManifestFetchesDuringCommit", fetches);
            return this;
        }

        /** Declare a container that virtual chunks may be read from. Containers are keyed by URL prefix. */
        public Builder virtualChunkContainer(VirtualChunkContainer container) {
            Objects.requireNonNull(container, "container");
            containers.removeIf(c -> c.urlPrefix().equals(container.urlPrefix()));
            containers.add(container);
            return this;
        }

        public RepositoryConfig build() {
            return new RepositoryConfig(this);
        }
    }
}
