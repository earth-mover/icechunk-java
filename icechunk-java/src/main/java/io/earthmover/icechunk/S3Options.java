package io.earthmover.icechunk;

import java.util.Objects;

/**
 * Where and how to reach an S3 bucket, or an S3-compatible service such as MinIO.
 *
 * <pre>{@code
 * S3Options options = S3Options.builder("my-bucket")
 *         .prefix("my-repo")
 *         .region("us-east-1")
 *         .build();
 * }</pre>
 */
public final class S3Options {
    private final String bucket;
    private final String prefix;
    private final String region;
    private final String endpointUrl;
    private final boolean allowHttp;
    private final boolean forcePathStyle;
    private final Integer networkStreamTimeoutSeconds;
    private final boolean requesterPays;
    private final S3Credentials credentials;

    private S3Options(Builder builder) {
        this.bucket = builder.bucket;
        this.prefix = builder.prefix;
        this.region = builder.region;
        this.endpointUrl = builder.endpointUrl;
        this.allowHttp = builder.allowHttp;
        this.forcePathStyle = builder.forcePathStyle;
        this.networkStreamTimeoutSeconds = builder.networkStreamTimeoutSeconds;
        this.requesterPays = builder.requesterPays;
        this.credentials = builder.credentials;
    }

    /**
     * A builder for {@code bucket}, with no prefix and credentials from the environment.
     *
     * @throws NullPointerException if {@code bucket} is null
     */
    public static Builder builder(String bucket) {
        return new Builder(bucket);
    }

    Json toJson() {
        return Json.object()
                .put("type", "s3")
                .put("bucket", bucket)
                .put("prefix", prefix)
                .put(
                        "options",
                        Json.object()
                                .put("region", region)
                                .put("endpoint_url", endpointUrl)
                                .put("allow_http", allowHttp)
                                .put("force_path_style", forcePathStyle)
                                .put("network_stream_timeout_seconds", networkStreamTimeoutSeconds)
                                .put("requester_pays", requesterPays))
                .put("credentials", credentials.toJson());
    }

    public static final class Builder {
        private final String bucket;
        private String prefix;
        private String region;
        private String endpointUrl;
        private boolean allowHttp;
        private boolean forcePathStyle;
        private Integer networkStreamTimeoutSeconds;
        private boolean requesterPays;
        private S3Credentials credentials = S3Credentials.fromEnvironment();

        private Builder(String bucket) {
            this.bucket = Objects.requireNonNull(bucket, "bucket");
        }

        /** The key prefix the repository lives under. */
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** The bucket's region. Defaults to the AWS SDK's region chain: environment variables, then profiles. */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /** A custom endpoint, for S3-compatible services. */
        public Builder endpointUrl(String endpointUrl) {
            this.endpointUrl = endpointUrl;
            return this;
        }

        /** Allow plain HTTP endpoints, typically for a local MinIO. Defaults to false. */
        public Builder allowHttp(boolean allowHttp) {
            this.allowHttp = allowHttp;
            return this;
        }

        /** Address the bucket in the URL path instead of the host name. Defaults to false. */
        public Builder forcePathStyle(boolean forcePathStyle) {
            this.forcePathStyle = forcePathStyle;
            return this;
        }

        /** How long a transfer may stall before it fails. Defaults to 10 seconds; 0 turns the check off. */
        public Builder networkStreamTimeoutSeconds(int seconds) {
            this.networkStreamTimeoutSeconds = seconds;
            return this;
        }

        /** Whether to send requests to a requester-pays bucket, billing them to the caller. Defaults to false. */
        public Builder requesterPays(boolean requesterPays) {
            this.requesterPays = requesterPays;
            return this;
        }

        /** Defaults to {@link S3Credentials#fromEnvironment()}. */
        public Builder credentials(S3Credentials credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        public S3Options build() {
            return new S3Options(this);
        }
    }
}
