package io.earthmover.icechunk;

/**
 * Credentials for reading one virtual chunk container.
 *
 * <p>A virtual chunk refers to bytes in an object outside the repository, such as a slice of a NetCDF or TIFF file.
 * The repository config declares the containers those objects live in; a container can only be read once the caller
 * authorizes it through {@link RepositoryOptions.Builder#authorizeVirtualChunkAccess}.
 */
public final class ContainerCredentials {
    private final Json json;

    private ContainerCredentials(Json json) {
        this.json = json;
    }

    public static ContainerCredentials s3(S3Credentials credentials) {
        return new ContainerCredentials(Json.object().put("type", "s3").put("credentials", credentials.toJson()));
    }

    public static ContainerCredentials gcs(GcsCredentials credentials) {
        return new ContainerCredentials(Json.object().put("type", "gcs").put("credentials", credentials.toJson()));
    }

    public static ContainerCredentials azure(AzureCredentials credentials) {
        return new ContainerCredentials(Json.object().put("type", "azure").put("credentials", credentials.toJson()));
    }

    /** Allow reading a {@code file://} container, which needs no credentials. */
    public static ContainerCredentials localFilesystem() {
        return new ContainerCredentials(Json.object().put("type", "local_filesystem"));
    }

    /** Allow reading an {@code http://} or {@code https://} container, which needs no credentials. */
    public static ContainerCredentials http() {
        return new ContainerCredentials(Json.object().put("type", "http"));
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return "ContainerCredentials";
    }
}
