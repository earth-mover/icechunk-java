package io.earthmover.icechunk;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** How to authenticate to Google Cloud Storage. */
public final class GcsCredentials {
    private final Json json;
    private final CredentialsCallback callback;

    private GcsCredentials(Json json) {
        this(json, null);
    }

    private GcsCredentials(Json json, CredentialsCallback callback) {
        this.json = json;
        this.callback = callback;
    }

    /** Use application default credentials from the environment. */
    public static GcsCredentials fromEnvironment() {
        return new GcsCredentials(Json.object().put("type", "from_env"));
    }

    /** Send unsigned requests, for public buckets. */
    public static GcsCredentials anonymous() {
        return new GcsCredentials(Json.object().put("type", "anonymous"));
    }

    /** Read a service account key file. */
    public static GcsCredentials serviceAccountFile(Path path) {
        return new GcsCredentials(
                Json.object().put("type", "service_account_file").put("path", path.toString()));
    }

    /** Use a service account key given as a JSON string. */
    public static GcsCredentials serviceAccountKey(String keyJson) {
        return new GcsCredentials(Json.object()
                .put("type", "service_account_key")
                .put("key", Objects.requireNonNull(keyJson, "keyJson")));
    }

    /** Read an application credentials file, as written by {@code gcloud auth application-default login}. */
    public static GcsCredentials applicationCredentialsFile(Path path) {
        return new GcsCredentials(
                Json.object().put("type", "application_credentials_file").put("path", path.toString()));
    }

    /** Use an OAuth bearer token. */
    public static GcsCredentials bearerToken(String token) {
        return bearerToken(token, null);
    }

    /**
     * Use an OAuth bearer token that expires at {@code expiresAfter}, which may be null. A {@link #refreshable}
     * supplier returns these.
     */
    public static GcsCredentials bearerToken(String token, Instant expiresAfter) {
        return new GcsCredentials(Json.object()
                .put("type", "bearer_token")
                .put("token", Objects.requireNonNull(token, "token"))
                .put("expires_after", expiresAfter == null ? null : expiresAfter.toString()));
    }

    /**
     * Fetch bearer tokens from {@code supplier} whenever the current one is about to expire. The supplier must return
     * {@link #bearerToken(String, Instant)}; see {@link S3Credentials#refreshable(Supplier)} for when and where it
     * runs.
     */
    public static GcsCredentials refreshable(Supplier<GcsCredentials> supplier) {
        return refreshable(supplier, null);
    }

    /** As {@link #refreshable(Supplier)}, waiting at most {@code timeout} for each answer (null for two minutes). */
    public static GcsCredentials refreshable(Supplier<GcsCredentials> supplier, Duration timeout) {
        CredentialsCallback callback = new CredentialsCallback(supplier, CredentialsCallback.Kind.GCS);
        return new GcsCredentials(S3Credentials.refreshableJson(timeout), callback);
    }

    boolean isRefreshable() {
        return callback != null;
    }

    Object[] callbacks() {
        return callback == null ? new Object[0] : new Object[] {callback};
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return isRefreshable() ? "GcsCredentials(refreshable)" : "GcsCredentials";
    }
}
