package io.earthmover.icechunk;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Where a repository's objects live.
 *
 * <p>A {@code Storage} is only needed to open or create a {@link Repository}; the repository keeps its own reference,
 * so closing the storage afterwards does not affect it.
 *
 * <pre>{@code
 * try (Storage storage = Storage.localFilesystem(Paths.get("/tmp/my-repo"))) {
 *     ...
 * }
 * }</pre>
 */
public final class Storage extends NativeHandle {
    private final String description;

    Storage(long handle, String description) {
        super(handle);
        this.description = description;
    }

    private static Storage open(Json spec, String description) {
        String json = spec.toString();
        return new Storage(NativeCall.runLong(call -> Native.storageOpen(call, json)), description);
    }

    /** Storage held in memory and discarded when the last reference to it closes. */
    public static Storage inMemory() {
        return open(Json.object().put("type", "in_memory"), "in-memory");
    }

    /** A directory on the local filesystem. */
    public static Storage localFilesystem(Path path) {
        Path absolute = path.toAbsolutePath();
        return open(
                Json.object().put("type", "local_filesystem").put("path", absolute.toString()), absolute.toString());
    }

    /** An S3 bucket or S3-compatible service. */
    public static Storage s3(S3Options options) {
        return open(options.toJson(), "s3");
    }

    /** A Google Cloud Storage bucket. */
    public static Storage gcs(GcsOptions options) {
        return open(options.toJson(), "gcs");
    }

    /** An Azure Blob Storage container. */
    public static Storage azure(AzureOptions options) {
        return open(options.toJson(), "azure");
    }

    /** A read-only repository served over HTTP(S), for example a public bucket's website endpoint. */
    public static Storage http(String url) {
        return http(url, null);
    }

    /** As {@link #http(String)}, with {@code object_store} HTTP client options. */
    public static Storage http(String url, Map<String, String> config) {
        Objects.requireNonNull(url, "url");
        return open(Json.object().put("type", "http").put("url", url).put("config", config), url);
    }

    @Override
    public String toString() {
        return "Storage(" + description + ")";
    }
}
