package io.earthmover.icechunk;

import java.nio.file.Path;
import java.util.Objects;

/** How to authenticate to Google Cloud Storage. */
public final class GcsCredentials {
    private final Json json;

    private GcsCredentials(Json json) {
        this.json = json;
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
        return new GcsCredentials(
                Json.object().put("type", "bearer_token").put("token", Objects.requireNonNull(token, "token")));
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return "GcsCredentials";
    }
}
