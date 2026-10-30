package io.earthmover.icechunk;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A place virtual chunks may be read from: every chunk whose location starts with {@link #urlPrefix()} is read
 * through this container's object store settings. Add it to {@link RepositoryConfig.Builder#virtualChunkContainer},
 * persist it with {@link Repository#saveConfig}, and grant readers access with {@link
 * RepositoryOptions.Builder#authorizeVirtualChunkAccess}.
 *
 * <pre>{@code
 * VirtualChunkContainer.s3("s3://source-bucket/").region("us-east-1").build();
 * VirtualChunkContainer.http("https://data.example.com/");
 * VirtualChunkContainer.localFilesystem("file:///data/", Paths.get("/data"));
 * }</pre>
 */
public final class VirtualChunkContainer {
    private final String urlPrefix;
    /** icechunk's {@code ObjectStoreConfig} as JSON. */
    private final String store;

    private VirtualChunkContainer(String urlPrefix, Json store) {
        this(urlPrefix, store.toString());
    }

    private VirtualChunkContainer(String urlPrefix, String store) {
        this.urlPrefix = Objects.requireNonNull(urlPrefix, "urlPrefix");
        this.store = store;
    }

    /** S3 or an S3-compatible service; configure it with the returned builder. */
    public static S3Builder s3(String urlPrefix) {
        return new S3Builder(urlPrefix);
    }

    /** Google Cloud Storage, with default options. */
    public static VirtualChunkContainer gcs(String urlPrefix) {
        return gcs(urlPrefix, Collections.emptyMap());
    }

    /** Google Cloud Storage, with object_store options such as {@code service_account_key}. */
    public static VirtualChunkContainer gcs(String urlPrefix, Map<String, String> options) {
        return new VirtualChunkContainer(urlPrefix, Json.object().put("gcs", Objects.requireNonNull(options)));
    }

    /** Azure Blob Storage in {@code account}. */
    public static VirtualChunkContainer azure(String urlPrefix, String account) {
        return new VirtualChunkContainer(
                urlPrefix,
                Json.object()
                        .put("azure", Collections.singletonMap("account", Objects.requireNonNull(account, "account"))));
    }

    /** Plain HTTP(S) servers, with default options. */
    public static VirtualChunkContainer http(String urlPrefix) {
        return http(urlPrefix, Collections.emptyMap());
    }

    /** Plain HTTP(S) servers, with object_store client options. */
    public static VirtualChunkContainer http(String urlPrefix, Map<String, String> options) {
        return new VirtualChunkContainer(urlPrefix, Json.object().put("http", Objects.requireNonNull(options)));
    }

    /** Files under {@code root} on the local filesystem; {@code urlPrefix} is a {@code file://} URL. */
    public static VirtualChunkContainer localFilesystem(String urlPrefix, Path root) {
        return new VirtualChunkContainer(
                urlPrefix,
                Json.object()
                        .put(
                                "local_file_system",
                                Objects.requireNonNull(root, "root")
                                        .toAbsolutePath()
                                        .toString()));
    }

    /** An in-memory object store, for tests. */
    public static VirtualChunkContainer inMemory(String urlPrefix) {
        return new VirtualChunkContainer(urlPrefix, "\"in_memory\"");
    }

    public String urlPrefix() {
        return urlPrefix;
    }

    Json toJson() {
        return Json.object().put("url_prefix", urlPrefix).putRaw("store", store);
    }

    @Override
    public String toString() {
        return "VirtualChunkContainer(" + urlPrefix + ")";
    }

    /** Settings for an S3 or S3-compatible container. */
    public static final class S3Builder {
        private final String urlPrefix;
        private String region;
        private String endpointUrl;
        private boolean anonymous;
        private boolean allowHttp;
        private boolean forcePathStyle;
        private boolean requesterPays;
        private boolean s3Compatible;

        private S3Builder(String urlPrefix) {
            this.urlPrefix = Objects.requireNonNull(urlPrefix, "urlPrefix");
        }

        public S3Builder region(String region) {
            this.region = region;
            return this;
        }

        /** The endpoint of an S3-compatible service; also marks the container as S3-compatible. */
        public S3Builder endpointUrl(String endpointUrl) {
            this.endpointUrl = endpointUrl;
            this.s3Compatible = endpointUrl != null;
            return this;
        }

        /** Send unsigned requests, for public buckets. Defaults to false. */
        public S3Builder anonymous(boolean anonymous) {
            this.anonymous = anonymous;
            return this;
        }

        public S3Builder allowHttp(boolean allowHttp) {
            this.allowHttp = allowHttp;
            return this;
        }

        public S3Builder forcePathStyle(boolean forcePathStyle) {
            this.forcePathStyle = forcePathStyle;
            return this;
        }

        public S3Builder requesterPays(boolean requesterPays) {
            this.requesterPays = requesterPays;
            return this;
        }

        public VirtualChunkContainer build() {
            Json options = Json.object()
                    .put("region", region)
                    .put("endpoint_url", endpointUrl)
                    .put("anonymous", anonymous)
                    .put("allow_http", allowHttp)
                    .put("force_path_style", forcePathStyle)
                    .put("requester_pays", requesterPays);
            return new VirtualChunkContainer(
                    urlPrefix, Json.object().put(s3Compatible ? "s3_compatible" : "s3", options));
        }
    }
}
