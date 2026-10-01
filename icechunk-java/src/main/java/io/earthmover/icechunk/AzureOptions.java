package io.earthmover.icechunk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Where and how to reach an Azure Blob Storage container. */
public final class AzureOptions {
    private final String account;
    private final String container;
    private final String prefix;
    private final AzureCredentials credentials;
    private final Map<String, String> config;

    private AzureOptions(Builder builder) {
        this.account = builder.account;
        this.container = builder.container;
        this.prefix = builder.prefix;
        this.credentials = builder.credentials;
        this.config = Collections.unmodifiableMap(new LinkedHashMap<>(builder.config));
    }

    public static Builder builder(String account, String container) {
        return new Builder(account, container);
    }

    Json toJson() {
        return Json.object()
                .put("type", "azure")
                .put("account", account)
                .put("container", container)
                .put("prefix", prefix)
                .put("credentials", credentials.toJson())
                .put("config", config);
    }

    public static final class Builder {
        private final String account;
        private final String container;
        private String prefix;
        private AzureCredentials credentials = AzureCredentials.fromEnvironment();
        private final Map<String, String> config = new LinkedHashMap<>();

        private Builder(String account, String container) {
            this.account = Objects.requireNonNull(account, "account");
            this.container = Objects.requireNonNull(container, "container");
        }

        /** The key prefix the repository lives under. */
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** Defaults to {@link AzureCredentials#fromEnvironment()}. */
        public Builder credentials(AzureCredentials credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        /** Set an {@code object_store} Azure option. Unknown keys are ignored. */
        public Builder config(String key, String value) {
            config.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public AzureOptions build() {
            return new AzureOptions(this);
        }
    }
}
