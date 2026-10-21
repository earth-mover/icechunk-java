package io.earthmover.icechunk;

import java.util.List;
import java.util.Map;

/**
 * What {@link Repository#garbageCollect} deleted, or would delete in a dry run.
 *
 * <p>A run can succeed with some deletes failing. Those objects stay in storage, and the next run deletes them; check
 * {@link #objectsFailedToDelete()} and {@link #skippedPhases()} to tell a partial run from a complete one.
 */
public final class GcSummary {
    private final long bytesDeleted;
    private final long chunksDeleted;
    private final long manifestsDeleted;
    private final long snapshotsDeleted;
    private final long transactionLogsDeleted;
    private final long objectsFailedToDelete;
    private final long throttledBatches;
    private final List<String> deleteErrors;
    private final List<String> skippedPhases;

    private GcSummary(Map<String, Object> fields) {
        bytesDeleted = JsonReader.integer(fields, "bytes_deleted");
        chunksDeleted = JsonReader.integer(fields, "chunks_deleted");
        manifestsDeleted = JsonReader.integer(fields, "manifests_deleted");
        snapshotsDeleted = JsonReader.integer(fields, "snapshots_deleted");
        transactionLogsDeleted = JsonReader.integer(fields, "transaction_logs_deleted");
        objectsFailedToDelete = JsonReader.integer(fields, "objects_failed_to_delete");
        throttledBatches = JsonReader.integer(fields, "throttled_batches");
        deleteErrors = JsonReader.stringList(fields, "delete_errors");
        skippedPhases = JsonReader.stringList(fields, "skipped_phases");
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

    /** Objects whose delete request failed. They are still garbage, and a later run deletes them. */
    public long objectsFailedToDelete() {
        return objectsFailedToDelete;
    }

    /** Delete requests the store throttled. Each was retried, and none failed. */
    public long throttledBatches() {
        return throttledBatches;
    }

    /** The first distinct messages of failed deletes, at most 10. */
    public List<String> deleteErrors() {
        return deleteErrors;
    }

    /**
     * The delete phases that did not run because an earlier phase had failed deletes, in order. Each phase deletes one
     * kind of object, after the kind that references it: {@code transaction_logs}, {@code manifests}, {@code chunks}.
     */
    public List<String> skippedPhases() {
        return skippedPhases;
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
                && transactionLogsDeleted == that.transactionLogsDeleted
                && objectsFailedToDelete == that.objectsFailedToDelete
                && throttledBatches == that.throttledBatches
                && deleteErrors.equals(that.deleteErrors)
                && skippedPhases.equals(that.skippedPhases);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bytesDeleted) ^ Long.hashCode(chunksDeleted);
    }

    @Override
    public String toString() {
        return "GcSummary{bytesDeleted=" + bytesDeleted + ", chunksDeleted=" + chunksDeleted + ", manifestsDeleted="
                + manifestsDeleted + ", snapshotsDeleted=" + snapshotsDeleted + ", transactionLogsDeleted="
                + transactionLogsDeleted + ", objectsFailedToDelete=" + objectsFailedToDelete + ", throttledBatches="
                + throttledBatches + ", deleteErrors=" + deleteErrors + ", skippedPhases=" + skippedPhases + "}";
    }
}
