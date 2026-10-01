package io.earthmover.icechunk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Where and how to reach a Google Cloud Storage bucket. */
public final class GcsOptions {
    private final String bucket;
    private final String prefix;
    private final GcsCredentials credentials;
    private final Map<String, String> config;

    private GcsOptions(Builder builder) {
        this.bucket = builder.bucket;
        this.prefix = builder.prefix;
        this.credentials = builder.credentials;
        this.config = Collections.unmodifiableMap(new LinkedHashMap<>(builder.config));
    }

    public static Builder builder(String bucket) {
        return new Builder(bucket);
    }

    Json toJson() {
        return Json.object()
                .put("type", "gcs")
                .put("bucket", bucket)
                .put("prefix", prefix)
                .put("credentials", credentials.toJson())
                .put("config", config);
    }

    public static final class Builder {
        private final String bucket;
        private String prefix;
        private GcsCredentials credentials = GcsCredentials.fromEnvironment();
        private final Map<String, String> config = new LinkedHashMap<>();

        private Builder(String bucket) {
            this.bucket = Objects.requireNonNull(bucket, "bucket");
        }

        /** The key prefix the repository lives under. */
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** Defaults to {@link GcsCredentials#fromEnvironment()}. */
        public Builder credentials(GcsCredentials credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        /**
         * Set an {@code object_store} GCS option, such as {@code google_service_account}. Unknown keys are ignored.
         */
        public Builder config(String key, String value) {
            config.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public GcsOptions build() {
            return new GcsOptions(this);
        }
    }
}
