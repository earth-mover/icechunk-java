package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.SnapshotId;
import io.earthmover.icechunk.Version;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;
import org.janelia.saalfeldlab.n5.N5URI;

/**
 * An icechunk repository, a version of it, and a node inside it, written as one URL in the pipe syntax of the Zarr URL
 * pipeline draft (formerly ZEP 8):
 *
 * <pre>
 * s3://bucket/repo|icechunk://branch.main/em/raw
 * gs://bucket/repo|icechunk://tag.v1
 * /data/repo|icechunk://GQQFH5G3AXKWZR5H33M0/labels
 * /data/repo.icechunk
 * </pre>
 *
 * <p>The part before the first {@code |} is the repository's location: {@code s3://}, {@code gs://},
 * {@code http(s)://}, {@code file://}, a local path, or a location a {@link RepositoryResolver} claims, such as
 * {@code al:org/repo} with the Arraylake resolver. The {@code icechunk:} stage names the version as
 * {@code //branch.NAME}, {@code //tag.NAME} or {@code //SNAPSHOT_ID}, followed by an optional node path; without a
 * version it is the main branch, and the stage may be just {@code icechunk}. A following {@code zarr3:} stage's path is joined onto the node path. A location ending in
 * {@code .icechunk} needs no stage. {@code %7C} is read as {@code |}, since URL fields percent-encode it.
 */
public final class IcechunkUrl {
    // Two or more characters, so a Windows drive letter is not taken for a scheme.
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]+:");
    private static final Pattern ICECHUNK_STAGE = Pattern.compile("(\\||%7c)icechunk(:|\\||%7c|$)");

    private final String location;
    private final String ref;
    private final Version version;
    private final String path;

    private IcechunkUrl(String location, String ref, String path, String url) {
        this.location = location;
        this.ref = ref;
        this.version = version(ref, url);
        this.path = path;
    }

    /** Whether {@code url} names an icechunk repository: it has an {@code icechunk:} stage or ends in .icechunk. */
    public static boolean claims(String url) {
        String s = url.trim().toLowerCase(Locale.ROOT);
        return ICECHUNK_STAGE.matcher(s).find() || stripSlashes(s).endsWith(".icechunk");
    }

    /** Parses a URI as n5 holds it, with names percent-encoded. A query or fragment is not part of the URL. */
    public static IcechunkUrl parse(URI uri) {
        String path = path(uri);
        if (uri.getScheme() == null) {
            return parse(path);
        }
        return parse(uri.getScheme() + ":" + (uri.getAuthority() == null ? "" : "//" + uri.getAuthority()) + path);
    }

    public static IcechunkUrl parse(String url) {
        String[] stages = url.trim().replace("%7C", "|").replace("%7c", "|").split("\\|", -1);
        String ref = "branch.main";
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < stages.length; i++) {
            String stage = stages[i].trim();
            int colon = stage.indexOf(':');
            String scheme = colon < 0 ? stage : stage.substring(0, colon);
            String rest = colon < 0 ? "" : stage.substring(colon + 1);
            if (scheme.equals("icechunk")) {
                if (rest.startsWith("//")) {
                    int slash = rest.indexOf('/', 2);
                    ref = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
                    rest = slash < 0 ? "" : rest.substring(slash + 1);
                } else if (rest.startsWith("@")) {
                    // An earlier draft's syntax, which Neuroglancer still uses. Read as a node path, it would open the
                    // wrong node without an error.
                    throw new IllegalArgumentException(
                            "write the version as icechunk://" + rest.substring(1) + " in " + url);
                }
                join(path, rest);
            } else if (scheme.equals("zarr3")) {
                join(path, rest);
            } else {
                throw new IllegalArgumentException("unsupported URL stage '" + stage + "' in " + url);
            }
        }
        String location = stripSlashes(stages[0].trim());
        if (location.isEmpty()) {
            throw new IllegalArgumentException("no repository location in " + url);
        }
        return new IcechunkUrl(location, ref, path.toString(), url);
    }

    private static Version version(String ref, String url) {
        String name = ref.startsWith("branch.") || ref.startsWith("tag.") ? ref.substring(ref.indexOf('.') + 1) : ref;
        if (name.isEmpty()) {
            throw new IllegalArgumentException("no version name in " + url);
        }
        if (ref.startsWith("branch.")) {
            return Version.branch(name);
        } else if (ref.startsWith("tag.")) {
            return Version.tag(name);
        } else {
            return Version.snapshot(SnapshotId.of(name));
        }
    }

    /**
     * The repository's location: a URL such as {@code s3://bucket/prefix}, or an absolute local path, with
     * {@code file:} URLs turned into paths, {@code ~} expanded, and no trailing slash.
     */
    public String location() {
        if (location.startsWith("file:")) {
            return Paths.get(location.substring("file:".length()).replaceFirst("^//", ""))
                    .toAbsolutePath()
                    .toString();
        }
        if (hasScheme(location)) {
            return location;
        }
        String home = System.getProperty("user.home");
        String path = location.equals("~") ? home : location.startsWith("~/") ? home + location.substring(1) : location;
        return Paths.get(path).toAbsolutePath().toString();
    }

    public Version version() {
        return version;
    }

    /** The node the URL names, relative to the repository root, without leading or trailing slashes. */
    public String path() {
        return path;
    }

    /** Appends {@code segment}, normalized, after a {@code /} unless {@code path} is empty. */
    static void join(StringBuilder path, String segment) {
        String s = N5URI.normalizeGroupPath(segment);
        if (s.isEmpty()) {
            return;
        }
        if (path.length() > 0) {
            path.append('/');
        }
        path.append(s);
    }

    static boolean hasScheme(String s) {
        return HAS_SCHEME.matcher(s).find();
    }

    /** The decoded path of {@code uri}, or if it is opaque, its scheme-specific part without the query. */
    static String path(URI uri) {
        if (uri.getPath() != null) {
            return uri.getPath();
        }
        String raw = uri.getRawSchemeSpecificPart();
        int query = raw.indexOf('?');
        String withoutQuery = query < 0 ? raw : raw.substring(0, query);
        return withoutQuery.isEmpty() ? "" : URI.create("x:" + withoutQuery).getSchemeSpecificPart();
    }

    static String stripSlashes(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    @Override
    public String toString() {
        return location + "|icechunk://" + ref + (path.isEmpty() ? "" : "/" + path);
    }
}
