package io.earthmover.icechunk;

import java.util.Map;

/** What {@link Repository#garbageCollect} deleted, or would delete in a dry run. */
public final class GcSummary {
    private final long bytesDeleted;
    private final long chunksDeleted;
    private final long manifestsDeleted;
    private final long snapshotsDeleted;
    private final long transactionLogsDeleted;

    private GcSummary(Map<String, Object> fields) {
        bytesDeleted = JsonReader.integer(fields, "bytes_deleted");
        chunksDeleted = JsonReader.integer(fields, "chunks_deleted");
        manifestsDeleted = JsonReader.integer(fields, "manifests_deleted");
        snapshotsDeleted = JsonReader.integer(fields, "snapshots_deleted");
        transactionLogsDeleted = JsonReader.integer(fields, "transaction_logs_deleted");
    }

    /** Read a {@code GcSummaryResult} document from the native layer. */
    static GcSummary fromJson(String json) {
        return new GcSummary(JsonReader.readObject(json));
    }

    /** The total size of the deleted objects of every kind. */
    public long bytesDeleted() {
        return bytesDeleted;
    }

    public long chunksDeleted() {
        return chunksDeleted;
    }

    public long manifestsDeleted() {
        return manifestsDeleted;
    }

    public long snapshotsDeleted() {
        return snapshotsDeleted;
    }

    public long transactionLogsDeleted() {
        return transactionLogsDeleted;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GcSummary)) {
            return false;
        }
        GcSummary that = (GcSummary) other;
        return bytesDeleted == that.bytesDeleted
                && chunksDeleted == that.chunksDeleted
                && manifestsDeleted == that.manifestsDeleted
                && snapshotsDeleted == that.snapshotsDeleted
                && transactionLogsDeleted == that.transactionLogsDeleted;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bytesDeleted) ^ Long.hashCode(chunksDeleted);
    }

    @Override
    public String toString() {
        return "GcSummary{bytesDeleted=" + bytesDeleted + ", chunksDeleted=" + chunksDeleted + ", manifestsDeleted="
                + manifestsDeleted + ", snapshotsDeleted=" + snapshotsDeleted + ", transactionLogsDeleted="
                + transactionLogsDeleted + "}";
    }
}
