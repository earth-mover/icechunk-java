package io.earthmover.icechunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional settings for {@link Session#commit(String, CommitOptions)}.
 *
 * <pre>{@code
 * session.commit("add temperature", CommitOptions.builder().metadata("author", "ian").build());
 * }</pre>
 */
public final class CommitOptions {
    private static final CommitOptions DEFAULTS = builder().build();

    private final String json;

    private CommitOptions(Builder builder) {
        Json json = Json.object();
        if (!builder.metadata.isEmpty()) {
            json.putValue("metadata", builder.metadata);
        }
        this.json = json.put("allow_empty", builder.allowEmpty).toString();
    }

    /** No metadata, and a commit with no changes throws. */
    public static CommitOptions defaults() {
        return DEFAULTS;
    }

    /** A builder that starts from {@link #defaults()}. */
    public static Builder builder() {
        return new Builder();
    }

    String toJson() {
        return json;
    }

    public static final class Builder {
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private boolean allowEmpty;

        private Builder() {}

        /**
         * Store {@code value} under {@code key} in the snapshot's metadata, which {@link SnapshotInfo#metadata()}
         * returns. The value must be a JSON value, which is one of:
         *
         * <ul>
         *   <li>a {@code String}, a {@code Boolean} or null
         *   <li>an {@code Integer}, {@code Long}, {@code Short} or {@code Byte}
         *   <li>a finite {@code Double} or {@code Float}
         *   <li>a {@code BigInteger} within the range of a 64-bit signed or unsigned integer
         *   <li>a {@code Map} with string keys or a {@code Collection}, whose values are JSON values, nested at most
         *       100 deep
         * </ul>
         */
        public Builder metadata(String key, Object value) {
            metadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        /** Store every entry of {@code metadata}, as {@link #metadata(String, Object)} does. */
        public Builder metadata(Map<String, ?> metadata) {
            for (Map.Entry<String, ?> entry : metadata.entrySet()) {
                metadata(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /** Whether a commit with no changes succeeds instead of throwing. Defaults to false. */
        public Builder allowEmpty(boolean allowEmpty) {
            this.allowEmpty = allowEmpty;
            return this;
        }

        /**
         * Build the options.
         *
         * @throws IllegalArgumentException if a metadata value is not a JSON value
         */
        public CommitOptions build() {
            return new CommitOptions(this);
        }
    }
}
