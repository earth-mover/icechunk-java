package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.net.URI;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.readdata.VolatileReadData;

/**
 * An {@link IcechunkKeyValueAccess} rooted at the node an {@link IcechunkUrl} names. Every path n5 gives it comes from
 * {@link #compose}, relative to that node.
 */
final class UrlKeyValueAccess implements KeyValueAccess {
    private final IcechunkKeyValueAccess store;
    /** The node the URL names, relative to the repository root. */
    private final String node;

    UrlKeyValueAccess(IcechunkKeyValueAccess store, String node) {
        this.store = store;
        this.node = node;
    }

    @Override
    public String normalize(String path) {
        return IcechunkUrl.hasScheme(path) ? IcechunkUrl.stripSlashes(path) : N5URI.normalizeGroupPath(path);
    }

    /**
     * The path relative to the node, percent-encoded, rather than n5's composition onto the URL's path. An opaque URL
     * such as {@code al:org/repo|icechunk:} has no path to compose onto, and n5 resolves each name as a URI, which reads
     * a name such as {@code t0:x} as a scheme. Encoding keeps {@code %}, {@code ?} or {@code #} in a name from being
     * read as syntax.
     */
    @Override
    public String compose(URI uri, String... components) {
        StringBuilder path = new StringBuilder();
        for (String component : components) {
            if (component != null) {
                IcechunkUrl.join(path, component);
            }
        }
        return encode(path.toString());
    }

    @Override
    public boolean exists(String normalPath) {
        return store.exists(inStore(normalPath));
    }

    @Override
    public long size(String normalPath) {
        return store.size(inStore(normalPath));
    }

    @Override
    public boolean isDirectory(String normalPath) {
        return store.isDirectory(inStore(normalPath));
    }

    @Override
    public boolean isFile(String normalPath) {
        return store.isFile(inStore(normalPath));
    }

    @Override
    public VolatileReadData createReadData(String normalPath) {
        return store.createReadData(inStore(normalPath));
    }

    @Override
    public void write(String normalPath, ReadData data) {
        store.write(inStore(normalPath), data);
    }

    @Override
    public String[] listDirectories(String normalPath) {
        return store.listDirectories(inStore(normalPath));
    }

    @Override
    public String[] list(String normalPath) {
        return store.list(inStore(normalPath));
    }

    @Override
    public void createDirectories(String normalPath) {
        store.createDirectories(inStore(normalPath));
    }

    @Override
    public void delete(String normalPath) {
        store.delete(inStore(normalPath));
    }

    /** The path {@link IcechunkKeyValueAccess} takes for a path {@link #compose} made: the node, then that path. */
    private String inStore(String normalPath) {
        StringBuilder key = new StringBuilder(node);
        IcechunkUrl.join(key, N5URI.getAsUri(normalPath).getPath());
        return encode(key.toString());
    }

    /**
     * Percent-encodes a path as n5 encodes names, colons included. Encoding it after a {@code /} keeps the URI
     * constructor from reading a first name such as {@code 0:x} as a scheme, which it rejects, and the encoded colon
     * keeps it from being read back as one.
     */
    private static String encode(String path) {
        return N5URI.encodeAsUriPath("/" + path).getRawPath().substring(1).replace(":", "%3A");
    }
}
