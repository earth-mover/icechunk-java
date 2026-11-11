package io.earthmover.icechunk;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** How to authenticate to S3 or an S3-compatible service. */
public final class S3Credentials {
    private final Json json;
    private final CredentialsCallback callback;

    private S3Credentials(Json json) {
        this(json, null);
    }

    private S3Credentials(Json json, CredentialsCallback callback) {
        this.json = json;
        this.callback = callback;
    }

    /** Use the standard AWS chain: environment variables, profiles, instance and container roles. */
    public static S3Credentials fromEnvironment() {
        return new S3Credentials(Json.object().put("type", "from_env"));
    }

    /** Send unsigned requests, for public buckets. */
    public static S3Credentials anonymous() {
        return new S3Credentials(Json.object().put("type", "anonymous"));
    }

    /** Use a fixed access key pair. */
    public static S3Credentials of(String accessKeyId, String secretAccessKey) {
        return of(accessKeyId, secretAccessKey, null);
    }

    /** Use fixed temporary credentials; {@code sessionToken} may be null. */
    public static S3Credentials of(String accessKeyId, String secretAccessKey, String sessionToken) {
        return of(accessKeyId, secretAccessKey, sessionToken, null);
    }

    /**
     * Use temporary credentials that expire at {@code expiresAfter}; {@code sessionToken} and {@code expiresAfter} may
     * be null. A {@link #refreshable} supplier returns these, so icechunk knows when to ask for new ones.
     */
    public static S3Credentials of(
            String accessKeyId, String secretAccessKey, String sessionToken, Instant expiresAfter) {
        return new S3Credentials(Json.object()
                .put("type", "static")
                .put("access_key_id", Objects.requireNonNull(accessKeyId, "accessKeyId"))
                .put("secret_access_key", Objects.requireNonNull(secretAccessKey, "secretAccessKey"))
                .put("session_token", sessionToken)
                .put("expires_after", expiresAfter == null ? null : expiresAfter.toString()));
    }

    /**
     * Fetch credentials from {@code supplier} whenever the current ones are about to expire.
     *
     * <p>The supplier must return {@link #of(String, String, String, Instant)} credentials; icechunk calls it again
     * shortly before {@code expiresAfter}, and only once if the credentials never expire. It runs on an icechunk
     * daemon thread, never on the thread whose call is waiting, so it may block, for example on an HTTP request, and
     * may use icechunk. If it throws, or does not answer within two minutes, the operation that needed credentials
     * fails with an {@link IcechunkException}.
     *
     * <p>Refreshable credentials work for {@link Storage#s3}, not yet for virtual chunk containers ({@link
     * Credentials#s3}). The supplier is referenced for as long as a storage, repository or session uses it.
     */
    public static S3Credentials refreshable(Supplier<S3Credentials> supplier) {
        return refreshable(supplier, null);
    }

    /** As {@link #refreshable(Supplier)}, waiting at most {@code timeout} for each answer (null for two minutes). */
    public static S3Credentials refreshable(Supplier<S3Credentials> supplier, Duration timeout) {
        CredentialsCallback callback = new CredentialsCallback(supplier, CredentialsCallback.Kind.S3);
        return new S3Credentials(refreshableJson(timeout), callback);
    }

    static Json refreshableJson(Duration timeout) {
        // The callback is the first, and only, object passed with the storage spec.
        Json json = Json.object().put("type", "refreshable").put("callback", 0);
        if (timeout != null) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            // Whole seconds, rounded up; toMillis works on Java 8, unlike toSeconds.
            json.put("timeout_seconds", Math.max(1, (timeout.toMillis() + 999) / 1000));
        }
        return json;
    }

    boolean isRefreshable() {
        return callback != null;
    }

    /** The Java objects the JSON refers to, passed to the native side with it. */
    Object[] callbacks() {
        return callback == null ? new Object[0] : new Object[] {callback};
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return isRefreshable() ? "S3Credentials(refreshable)" : "S3Credentials";
    }
}
