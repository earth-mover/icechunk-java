package io.earthmover.icechunk;

import java.util.Objects;

/** How to authenticate to S3 or an S3-compatible service. */
public final class S3Credentials {
    private final Json json;

    private S3Credentials(Json json) {
        this.json = json;
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
        return new S3Credentials(Json.object()
                .put("type", "static")
                .put("access_key_id", Objects.requireNonNull(accessKeyId, "accessKeyId"))
                .put("secret_access_key", Objects.requireNonNull(secretAccessKey, "secretAccessKey"))
                .put("session_token", sessionToken));
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return "S3Credentials";
    }
}
