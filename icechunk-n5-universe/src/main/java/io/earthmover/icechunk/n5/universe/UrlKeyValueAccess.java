package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.net.URI;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.readdata.VolatileReadData;

/**
 * An {@link IcechunkKeyValueAccess} rooted at the node an {@link IcechunkUrl} names. n5 composes every path from the
 * URL it was opened with, so each path is the URL's path, then a path relative to the node.
 */
final class UrlKeyValueAccess implements KeyValueAccess {
    private final IcechunkKeyValueAccess store;
    /** The decoded path of the URL, without trailing slashes, which every composed path starts with. */
    private final String root;
    /** The node the URL names, relative to the repository root. */
    private final String node;

    UrlKeyValueAccess(IcechunkKeyValueAccess store, URI url, String node) {
        this.store = store;
        this.root = IcechunkUrl.stripSlashes(IcechunkUrl.path(url));
        this.node = node;
    }

    @Override
    public String normalize(String path) {
        return IcechunkUrl.hasScheme(path) ? IcechunkUrl.stripSlashes(path) : N5URI.normalizeGroupPath(path);
    }

    /**
     * n5 composes paths onto the URL's path, which an opaque URL such as {@code al:org/repo|icechunk:} lacks, so for
     * those the composed path is relative to the node.
     */
    @Override
    public String compose(URI uri, String... components) {
        if (!uri.isOpaque()) {
            return KeyValueAccess.super.compose(uri, components);
        }
        StringBuilder path = new StringBuilder();
        for (String component : components) {
            if (component != null) {
                IcechunkUrl.join(path, component);
            }
        }
        return path.toString();
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

    /**
     * The path {@link IcechunkKeyValueAccess} takes for a path n5 gives: the node, then the part of the path after the
     * URL's, percent-encoded as n5 encodes names. A path without the URL's in front is relative to the node.
     */
    private String inStore(String normalPath) {
        String path = IcechunkUrl.path(N5URI.getAsUri(normalPath));
        String relative = path.startsWith(root) ? path.substring(root.length()) : path;
        StringBuilder key = new StringBuilder(node);
        IcechunkUrl.join(key, relative);
        return N5URI.encodeAsUriPath(key.toString()).getRawPath();
    }
}
