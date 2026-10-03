package io.earthmover.icechunk.n5.universe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthmover.icechunk.AzureCredentials;
import io.earthmover.icechunk.Credentials;
import io.earthmover.icechunk.GcsCredentials;
import io.earthmover.icechunk.GcsOptions;
import io.earthmover.icechunk.IcechunkException;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.RepositoryOptions;
import io.earthmover.icechunk.S3Credentials;
import io.earthmover.icechunk.S3Options;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.universe.KeyValueAccessProvider;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

/**
 * Opens {@link IcechunkUrl icechunk URLs} through n5-universe, so any tool that opens a location with
 * {@link N5Factory}, such as n5-ij's importer, opens {@code s3://bucket/repo|icechunk:@branch.main/em/raw} as a Zarr v3
 * container rooted at that node.
 *
 * <p>n5-universe finds this provider through {@code META-INF/services}, so putting the jar on the classpath is enough.
 * URLs open read-only: writes need a commit, which a URL has no place for.
 *
 * <p>S3 and Google Cloud Storage repositories are opened with the credentials the environment provides, such as the
 * AWS default chain, and anonymously if that fails, so public repositories open without credentials. An S3 bucket's
 * region is looked up from the bucket. Each repository is opened once and kept open; every URL opens a new session, so
 * a branch is read at its tip when the URL is opened.
 *
 * <p>Virtual chunks are read from every container the repository declares, anonymously, so no credentials are ever
 * sent to a location the repository names. Containers on the local file system are not authorized. Setting the system
 * property {@code icechunk.virtualChunks} to {@code none} authorizes no containers.
 */
public final class IcechunkKeyValueAccessProvider implements KeyValueAccessProvider {
    private static final Map<String, Repository> REPOSITORIES = new ConcurrentHashMap<>();

    @Override
    public boolean test(URI uri) {
        return IcechunkUrl.claims(uri.toString());
    }

    @Override
    public KeyValueAccess apply(URI uri, N5Factory factory, boolean readOnly) {
        if (!readOnly) {
            throw new N5Exception("icechunk URLs open read-only; to write, use IcechunkKeyValueAccess on a writable"
                    + " session and commit it: " + uri);
        }
        IcechunkUrl url = IcechunkUrl.parse(uri);
        Session session;
        try {
            session = repository(url.location()).readonlySession(url.version());
        } catch (IcechunkException e) {
            throw new N5Exception.N5IOException("cannot open " + url + ": " + e.getMessage(), e);
        }
        return new UrlKeyValueAccess(new IcechunkKeyValueAccess(session), uri, url.path());
    }

    /** Opens outside the map: opening can take seconds, and computeIfAbsent would block other locations meanwhile. */
    private static Repository repository(String location) {
        Repository cached = REPOSITORIES.get(location);
        if (cached != null) {
            return cached;
        }
        Repository opened = open(location);
        Repository raced = REPOSITORIES.putIfAbsent(location, opened);
        if (raced == null) {
            return opened;
        }
        opened.close();
        return raced;
    }

    private static Repository open(String location) {
        if (location.startsWith("s3://")) {
            String[] bucketAndPrefix = bucketAndPrefix(location, "s3://");
            String region = bucketRegion(bucketAndPrefix[0]);
            Function<S3Credentials, Storage> storage = credentials -> {
                S3Options.Builder options = S3Options.builder(bucketAndPrefix[0])
                        .prefix(bucketAndPrefix[1])
                        .credentials(credentials);
                if (region != null) {
                    options.region(region);
                }
                return Storage.s3(options.build());
            };
            return openEitherWay(
                    () -> storage.apply(S3Credentials.fromEnvironment()),
                    () -> storage.apply(S3Credentials.anonymous()));
        }
        if (location.startsWith("gs://")) {
            String[] bucketAndPrefix = bucketAndPrefix(location, "gs://");
            Function<GcsCredentials, Storage> storage =
                    credentials -> Storage.gcs(GcsOptions.builder(bucketAndPrefix[0])
                            .prefix(bucketAndPrefix[1])
                            .credentials(credentials)
                            .build());
            return openEitherWay(
                    () -> storage.apply(GcsCredentials.fromEnvironment()),
                    () -> storage.apply(GcsCredentials.anonymous()));
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return open(Storage.http(location));
        }
        if (IcechunkUrl.hasScheme(location)) {
            throw new N5Exception("unsupported icechunk repository location: " + location);
        }
        return open(Storage.localFilesystem(Paths.get(location)));
    }

    /** Opens with the environment's credentials, then anonymously; if both fail, throws the first failure. */
    private static Repository openEitherWay(Supplier<Storage> withCredentials, Supplier<Storage> anonymously) {
        try {
            return open(withCredentials.get());
        } catch (N5Exception credentialed) {
            try {
                return open(anonymously.get());
            } catch (N5Exception anonymous) {
                credentialed.addSuppressed(anonymous);
                throw credentialed;
            }
        }
    }

    private static Repository open(Storage storage) {
        try (Storage s = storage) {
            Repository repo = Repository.open(s);
            if ("none".equals(System.getProperty("icechunk.virtualChunks"))) {
                return repo;
            }
            Map<String, Credentials> containers = anonymousAccess(repo.configJson());
            if (containers.isEmpty()) {
                return repo;
            }
            repo.close();
            RepositoryOptions.Builder options = RepositoryOptions.builder();
            containers.forEach(options::authorizeVirtualChunkAccess);
            return Repository.open(s, options.build());
        } catch (IcechunkException e) {
            throw new N5Exception.N5IOException("cannot open icechunk repository: " + e.getMessage(), e);
        }
    }

    /**
     * Anonymous credentials for each virtual chunk container in a repository's configuration, by URL prefix. Local
     * file system containers are left out: a repository opened from a URL should not read local files unasked.
     */
    static Map<String, Credentials> anonymousAccess(String configJson) {
        Map<String, Credentials> access = new LinkedHashMap<>();
        JsonElement containers =
                JsonParser.parseString(configJson).getAsJsonObject().get("virtual_chunk_containers");
        if (containers == null || !containers.isJsonObject()) {
            return access;
        }
        for (Map.Entry<String, JsonElement> container :
                containers.getAsJsonObject().entrySet()) {
            JsonObject store = container.getValue().getAsJsonObject().getAsJsonObject("store");
            String type = store == null || store.size() != 1
                    ? ""
                    : store.keySet().iterator().next();
            Credentials credentials = anonymous(type);
            if (credentials != null) {
                access.put(container.getKey(), credentials);
            }
        }
        return access;
    }

    private static Credentials anonymous(String storeType) {
        switch (storeType) {
            case "http":
                return Credentials.http();
            case "s3":
            case "s3_compatible":
            case "tigris":
                return Credentials.s3(S3Credentials.anonymous());
            case "gcs":
                return Credentials.gcs(GcsCredentials.anonymous());
            case "azure":
                return Credentials.azure(AzureCredentials.anonymous());
            default:
                return null;
        }
    }

    private static String[] bucketAndPrefix(String location, String scheme) {
        String rest = location.substring(scheme.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? new String[] {rest, ""} : new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    /**
     * The region S3 reports for {@code bucket}, or null if the lookup fails. S3 answers a request for any bucket with
     * its region, with or without credentials.
     */
    private static String bucketRegion(String bucket) {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL("https://s3.amazonaws.com/" + bucket).openConnection();
            try {
                connection.setRequestMethod("HEAD");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.getResponseCode();
                return connection.getHeaderField("x-amz-bucket-region");
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
