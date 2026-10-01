package io.earthmover.icechunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional settings for opening or creating a {@link Repository}.
 *
 * <pre>{@code
 * RepositoryOptions options = RepositoryOptions.builder()
 *         .authorizeVirtualChunkAccess("s3://source-bucket/", ContainerCredentials.s3(S3Credentials.anonymous()))
 *         .build();
 * }</pre>
 */
public final class RepositoryOptions {
    private static final RepositoryOptions DEFAULTS = builder().build();

    private final String json;

    private RepositoryOptions(Builder builder) {
        Map<String, Json> credentials = new LinkedHashMap<>();
        builder.credentials.forEach((prefix, value) -> credentials.put(prefix, value.toJson()));
        this.json = Json.object()
                .putRaw("config", builder.configJson)
                .put("virtual_chunk_credentials", credentials)
                .put("spec_version", builder.specVersion)
                .put("check_clean_root", builder.checkCleanRoot)
                .toString();
    }

    public static RepositoryOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    String toJson() {
        return json;
    }

    public static final class Builder {
        private String configJson;
        private final Map<String, ContainerCredentials> credentials = new LinkedHashMap<>();
        private Integer specVersion;
        private boolean checkCleanRoot = true;

        private Builder() {}

        /**
         * Repository configuration as a JSON document in icechunk's {@code RepositoryConfig} format, layered over the
         * config stored in the repository. {@link Repository#configJson()} returns a document in this format.
         */
        public Builder configJson(String configJson) {
            this.configJson = configJson;
            return this;
        }

        /** Allow reading virtual chunks from the container whose URL starts with {@code urlPrefix}. */
        public Builder authorizeVirtualChunkAccess(String urlPrefix, ContainerCredentials credentials) {
            this.credentials.put(
                    Objects.requireNonNull(urlPrefix, "urlPrefix"), Objects.requireNonNull(credentials, "credentials"));
            return this;
        }

        /** The on-disk format version for a new repository. Defaults to the latest; ignored when opening. */
        public Builder specVersion(int specVersion) {
            this.specVersion = specVersion;
            return this;
        }

        /**
         * Whether creating a repository fails if the storage location already holds objects. Defaults to true;
         * ignored when opening.
         */
        public Builder checkCleanRoot(boolean checkCleanRoot) {
            this.checkCleanRoot = checkCleanRoot;
            return this;
        }

        public RepositoryOptions build() {
            return new RepositoryOptions(this);
        }
    }
}
