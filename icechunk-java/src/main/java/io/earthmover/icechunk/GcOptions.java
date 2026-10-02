package io.earthmover.icechunk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Which unreachable objects {@link Repository#garbageCollect(GcOptions)} deletes, and how much work it does at once.
 *
 * <p>An object is unreachable when no branch, tag or {@linkplain Builder#extraRoots extra root} leads to it. Each kind
 * of object has its own cutoff, and unreachable objects written before it are deleted; a kind with no cutoff is kept.
 *
 * <pre>{@code
 * GcOptions options = GcOptions.builder()
 *         .deleteObjectsOlderThan(cutoff)
 *         .extraRoots(undoHistory)
 *         .build();
 * }</pre>
 */
public final class GcOptions {
    private static final int MAX_U16 = 0xFFFF;

    private final String json;

    private GcOptions(Builder builder) {
        List<String> roots = new ArrayList<>(builder.extraRoots.size());
        for (SnapshotId id : builder.extraRoots) {
            roots.add(id.toString());
        }
        this.json = Json.object()
                .putValue("extra_roots", roots)
                .put("delete_chunks_older_than", builder.chunksCutoff)
                .put("delete_manifests_older_than", builder.manifestsCutoff)
                .put("delete_transaction_logs_older_than", builder.transactionLogsCutoff)
                .put("delete_snapshots_older_than", builder.snapshotsCutoff)
                .put("max_snapshots_in_memory", builder.maxSnapshotsInMemory)
                .put("max_compressed_manifest_mem_bytes", builder.maxCompressedManifestMemBytes)
                .put("max_concurrent_manifest_fetches", builder.maxConcurrentManifestFetches)
                .put("dry_run", builder.dryRun)
                .toString();
    }

    /**
     * A builder with no cutoffs, which deletes nothing until one is set, and the default limits: 50 snapshots in
     * memory, 512 MiB of compressed manifests, and 500 concurrent manifest fetches.
     */
    public static Builder builder() {
        return new Builder();
    }

    String toJson() {
        return json;
    }

    public static final class Builder {
        private final List<SnapshotId> extraRoots = new ArrayList<>();
        private String chunksCutoff;
        private String manifestsCutoff;
        private String transactionLogsCutoff;
        private String snapshotsCutoff;
        private int maxSnapshotsInMemory = 50;
        private long maxCompressedManifestMemBytes = 512L * 1024 * 1024;
        private int maxConcurrentManifestFetches = 500;
        private boolean dryRun;

        private Builder() {}

        /**
         * Delete unreachable objects of every kind written before {@code cutoff}.
         *
         * @throws IllegalArgumentException if {@code cutoff} is outside the years 0000 to 9999
         */
        public Builder deleteObjectsOlderThan(Instant cutoff) {
            return deleteChunksOlderThan(cutoff)
                    .deleteManifestsOlderThan(cutoff)
                    .deleteTransactionLogsOlderThan(cutoff)
                    .deleteSnapshotsOlderThan(cutoff);
        }

        /**
         * Delete unreachable chunks written before {@code cutoff}.
         *
         * @throws IllegalArgumentException if {@code cutoff} is outside the years 0000 to 9999
         */
        public Builder deleteChunksOlderThan(Instant cutoff) {
            chunksCutoff = Json.timestamp("cutoff", cutoff);
            return this;
        }

        /**
         * Delete unreachable manifests written before {@code cutoff}.
         *
         * @throws IllegalArgumentException if {@code cutoff} is outside the years 0000 to 9999
         */
        public Builder deleteManifestsOlderThan(Instant cutoff) {
            manifestsCutoff = Json.timestamp("cutoff", cutoff);
            return this;
        }

        /**
         * Delete unreachable transaction logs written before {@code cutoff}.
         *
         * @throws IllegalArgumentException if {@code cutoff} is outside the years 0000 to 9999
         */
        public Builder deleteTransactionLogsOlderThan(Instant cutoff) {
            transactionLogsCutoff = Json.timestamp("cutoff", cutoff);
            return this;
        }

        /**
         * Delete unreachable snapshots written before {@code cutoff}.
         *
         * @throws IllegalArgumentException if {@code cutoff} is outside the years 0000 to 9999
         */
        public Builder deleteSnapshotsOlderThan(Instant cutoff) {
            snapshotsCutoff = Json.timestamp("cutoff", cutoff);
            return this;
        }

        /**
         * Add {@code snapshots} to the roots: they stay reachable, along with everything they lead to, even if no branch
         * or tag does, such as snapshots a {@link Repository#resetBranch} moved away from. Each must still be in the
         * repository, or {@link Repository#garbageCollect(GcOptions)} throws {@link IcechunkException}.
         */
        public Builder extraRoots(Collection<SnapshotId> snapshots) {
            for (SnapshotId id : snapshots) {
                extraRoots.add(Objects.requireNonNull(id, "snapshot id"));
            }
            return this;
        }

        /** The most snapshots held in memory at once. Defaults to 50; must be between 1 and 65535. */
        public Builder maxSnapshotsInMemory(int maxSnapshotsInMemory) {
            this.maxSnapshotsInMemory = checkU16("maxSnapshotsInMemory", maxSnapshotsInMemory);
            return this;
        }

        /** The most memory compressed manifests in flight may use. Defaults to 512 MiB; must be positive. */
        public Builder maxCompressedManifestMemBytes(long maxCompressedManifestMemBytes) {
            if (maxCompressedManifestMemBytes < 1) {
                throw new IllegalArgumentException(
                        "maxCompressedManifestMemBytes must be positive: " + maxCompressedManifestMemBytes);
            }
            this.maxCompressedManifestMemBytes = maxCompressedManifestMemBytes;
            return this;
        }

        /** The most manifests fetched concurrently. Defaults to 500; must be between 1 and 65535. */
        public Builder maxConcurrentManifestFetches(int maxConcurrentManifestFetches) {
            this.maxConcurrentManifestFetches = checkU16("maxConcurrentManifestFetches", maxConcurrentManifestFetches);
            return this;
        }

        /** Whether to report what would be deleted without deleting it. Defaults to false. */
        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public GcOptions build() {
            return new GcOptions(this);
        }

        private static int checkU16(String name, int value) {
            if (value < 1 || value > MAX_U16) {
                throw new IllegalArgumentException(name + " must be between 1 and " + MAX_U16 + ": " + value);
            }
            return value;
        }
    }
}
