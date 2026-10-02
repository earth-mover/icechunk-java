package io.earthmover.icechunk;

/**
 * Credentials for reading one virtual chunk container, matching icechunk-python's {@code Credentials}.
 *
 * <p>A virtual chunk refers to bytes in an object outside the repository, such as a slice of a NetCDF or TIFF file.
 * The repository config declares the containers those objects live in; a container can only be read once the caller
 * authorizes it through {@link RepositoryOptions.Builder#authorizeVirtualChunkAccess}.
 */
public final class Credentials {
    private final Json json;

    private Credentials(Json json) {
        this.json = json;
    }

    /** Read an S3 or S3-compatible container with {@code credentials}. */
    public static Credentials s3(S3Credentials credentials) {
        return new Credentials(Json.object().put("type", "s3").put("credentials", credentials.toJson()));
    }

    /** Read a Google Cloud Storage container with {@code credentials}. */
    public static Credentials gcs(GcsCredentials credentials) {
        return new Credentials(Json.object().put("type", "gcs").put("credentials", credentials.toJson()));
    }

    /** Read an Azure Blob Storage container with {@code credentials}. */
    public static Credentials azure(AzureCredentials credentials) {
        return new Credentials(Json.object().put("type", "azure").put("credentials", credentials.toJson()));
    }

    /** Allow reading a {@code file://} container, which needs no credentials. */
    public static Credentials localFilesystem() {
        return new Credentials(Json.object().put("type", "local_filesystem"));
    }

    /** Allow reading an {@code http://} or {@code https://} container, which needs no credentials. */
    public static Credentials http() {
        return new Credentials(Json.object().put("type", "http"));
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return "Credentials";
    }
}
