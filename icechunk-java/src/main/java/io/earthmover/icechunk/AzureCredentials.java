package io.earthmover.icechunk;

import java.util.Objects;

/** How to authenticate to Azure Blob Storage. */
public final class AzureCredentials {
    private final Json json;

    private AzureCredentials(Json json) {
        this.json = json;
    }

    /** Use credentials from the environment. */
    public static AzureCredentials fromEnvironment() {
        return new AzureCredentials(Json.object().put("type", "from_env"));
    }

    /** Send unsigned requests, for public containers. */
    public static AzureCredentials anonymous() {
        return new AzureCredentials(Json.object().put("type", "anonymous"));
    }

    /** Use a storage account access key. */
    public static AzureCredentials accessKey(String key) {
        return new AzureCredentials(
                Json.object().put("type", "access_key").put("key", Objects.requireNonNull(key, "key")));
    }

    /** Use a shared access signature token. */
    public static AzureCredentials sasToken(String token) {
        return new AzureCredentials(
                Json.object().put("type", "sas_token").put("token", Objects.requireNonNull(token, "token")));
    }

    /** Use an OAuth bearer token. */
    public static AzureCredentials bearerToken(String token) {
        return new AzureCredentials(
                Json.object().put("type", "bearer_token").put("token", Objects.requireNonNull(token, "token")));
    }

    Json toJson() {
        return json;
    }

    /** Returns a description without secrets. */
    @Override
    public String toString() {
        return "AzureCredentials";
    }
}
