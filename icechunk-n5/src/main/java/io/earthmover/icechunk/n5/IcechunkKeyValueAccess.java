package io.earthmover.icechunk.n5;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.earthmover.icechunk.ByteRange;
import io.earthmover.icechunk.IcechunkException;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Store;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Exception.N5IOException;
import org.janelia.saalfeldlab.n5.N5Exception.N5NoSuchKeyException;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.readdata.LazyRead;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.readdata.VolatileReadData;

/**
 * An N5 {@link KeyValueAccess} over the {@link Store} of an icechunk session, so that n5-zarr's Zarr v3 reader and
 * writer, and the tools built on them, can read and write an icechunk repository.
 *
 * <p>icechunk holds Zarr v3 only, so open it with n5-zarr's Zarr v3 classes, or with n5-universe's
 * {@code StorageFormat.ZARR3}. The N5 and Zarr v2 formats store keys icechunk cannot hold. Paths are relative to the
 * repository root; only the path of the container's URI is used, so any URI whose path is empty or {@code /} works:
 *
 * <pre>{@code
 * KeyValueAccess kva = new IcechunkKeyValueAccess(session);
 * N5Writer n5 = new ZarrV3KeyValueWriter(kva, "", new GsonBuilder(), false);
 * N5Writer cached = factory.openWriter(StorageFormat.ZARR3, kva, URI.create("icechunk://my-repo"));
 * }</pre>
 *
 * <p>n5-universe's {@code N5FactoryWithCache} caches readers and writers by URI, so give each repository its own.
 *
 * <p>icechunk stores groups and arrays rather than files and directories. A path is a directory if it is the root or
 * holds a {@code zarr.json}, and {@link #createDirectories} does nothing: a group or array exists once its
 * {@code zarr.json} is written. A group cannot become an array, or an array a group, by rewriting its
 * {@code zarr.json}: icechunk refuses the write. Writes go to the session and become a snapshot when it commits.
 *
 * <p>Over a read-only session, the first listing reads every group and array's {@code zarr.json} from the session's
 * snapshot in one call, and later listings, existence checks and {@code zarr.json} reads are answered from it. A writable session is asked each time, since writes would make that copy stale.
 *
 * <p>Errors from icechunk are thrown as {@link N5IOException}, with the {@link IcechunkException} as the cause. A path
 * that is not a key icechunk can hold, such as a group's path, is not a file; reading or writing it throws
 * {@link IllegalArgumentException}.
 */
public final class IcechunkKeyValueAccess implements KeyValueAccess {
    private static final String METADATA = "zarr.json";

    private final Supplier<Store> store;
    // Swapped when the supplier returns another store.
    private volatile Nodes cache;

    /** Read and write through {@code session}'s store. */
    public IcechunkKeyValueAccess(Session session) {
        this(Objects.requireNonNull(session, "session")::store);
    }

    /**
     * Read and write through the store {@code store} returns, asked again for each operation. A caller that commits
     * and continues in a new session can swap sessions without replacing the readers and writers built on this
     * object. A read started with {@link #createReadData} keeps the store it started with.
     */
    public IcechunkKeyValueAccess(Supplier<Store> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public String normalize(String path) {
        return N5URI.normalizeGroupPath(path);
    }

    @Override
    public boolean exists(String normalPath) {
        return isDirectory(normalPath) || isFile(normalPath);
    }

    @Override
    public boolean isFile(String normalPath) {
        String key = key(normalPath);
        if (key.isEmpty()) {
            return false;
        }
        Nodes cached = isMetadata(key) ? nodes(normalPath, false) : null;
        if (cached != null) {
            return cached.metadata.containsKey(nodePath(key));
        }
        try {
            return call(normalPath, () -> store.get().exists(key));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean isDirectory(String normalPath) {
        try {
            return isNode(key(normalPath), normalPath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public long size(String normalPath) {
        return size(store.get(), key(normalPath), normalPath);
    }

    @Override
    public VolatileReadData createReadData(String normalPath) {
        String key = key(normalPath);
        Nodes cached = isMetadata(key) ? nodes(normalPath, false) : null;
        String document = cached != null ? cached.metadata.get(nodePath(key)) : null;
        if (document != null) {
            return VolatileReadData.from(new DocumentRead(document.getBytes(UTF_8)));
        }
        return VolatileReadData.from(new StoreRead(store.get(), key, normalPath));
    }

    @Override
    public void write(String normalPath, ReadData data) {
        String key = key(normalPath);
        run(normalPath, () -> store.get().set(key, data.toByteBuffer()));
    }

    /** The names of the entries one level below {@code normalPath}: groups and arrays, and its {@code zarr.json}. */
    @Override
    public String[] list(String normalPath) {
        return children(normalPath).toArray(new String[0]);
    }

    /** The names of the groups and arrays one level below {@code normalPath}. */
    @Override
    public String[] listDirectories(String normalPath) {
        String key = key(normalPath);
        Nodes cached = nodes(normalPath, true);
        if (cached != null && cached.metadata.containsKey(key)) {
            return cached.children(key).toArray(new String[0]);
        }
        List<String> directories = new ArrayList<>();
        for (String child : children(normalPath)) {
            if (isNode(key.isEmpty() ? child : key + "/" + child, normalPath)) {
                directories.add(child);
            }
        }
        return directories.toArray(new String[0]);
    }

    /** Does nothing: icechunk has no directories, and a group or array exists once its metadata is written. */
    @Override
    public void createDirectories(String normalPath) {}

    /** Delete the group or array at {@code normalPath} and everything below it, or the value at that key. */
    @Override
    public void delete(String normalPath) {
        String key = key(normalPath);
        // A group's path can also parse as a chunk key, such as "raw/c" for a group c in a group raw, so check for a
        // node first.
        boolean node = isNode(key, normalPath);
        run(normalPath, () -> {
            if (node) {
                store.get().deleteDir(key);
                return;
            }
            try {
                store.get().delete(key);
            } catch (IllegalArgumentException e) {
                store.get().deleteDir(key);
            }
        });
    }

    /** Whether {@code key} is the root or a group or array. */
    private boolean isNode(String key, String normalPath) {
        if (key.isEmpty()) {
            return true;
        }
        Nodes cached = nodes(normalPath, false);
        if (cached != null) {
            return cached.metadata.containsKey(key);
        }
        return call(normalPath, () -> store.get().exists(key + "/" + METADATA));
    }

    /**
     * The listing of the current store if it is read-only, or null. Only a listing reads it, so that opening one
     * dataset by its path costs a few lookups rather than every node's metadata.
     */
    private Nodes nodes(String normalPath, boolean load) {
        Store current = store.get();
        Nodes cached = cache;
        if (cached == null || cached.store != current) {
            if (!load) {
                return null;
            }
            cached = new Nodes(current, call(normalPath, () -> current.isReadOnly() ? current.listNodes() : null));
            cache = cached;
        }
        return cached.metadata == null ? null : cached;
    }

    private static boolean isMetadata(String key) {
        return key.equals(METADATA) || key.endsWith("/" + METADATA);
    }

    /** The node a {@code zarr.json} key belongs to. */
    private static String nodePath(String metadataKey) {
        return metadataKey.equals(METADATA)
                ? ""
                : metadataKey.substring(0, metadataKey.length() - METADATA.length() - 1);
    }

    /**
     * The store key for a path n5 gives. n5 composes paths as URIs from the container's URI, so take the path, which
     * drops the scheme and authority and decodes the names n5 percent-encoded, then normalize it without a leading
     * slash.
     */
    private String key(String normalPath) {
        return normalize(N5URI.getAsUri(normalPath).getPath());
    }

    private List<String> children(String normalPath) {
        String key = key(normalPath);
        Nodes cached = nodes(normalPath, true);
        if (cached != null && cached.metadata.containsKey(key)) {
            return cached.listDir(key);
        }
        return call(normalPath, () -> store.get().listDir(key));
    }

    private static long size(Store store, String key, String normalPath) {
        return call(normalPath, () -> store.getSize(key)).orElseThrow(() -> noSuchKey(normalPath));
    }

    private static N5NoSuchKeyException noSuchKey(String normalPath) {
        return new N5NoSuchKeyException("no such key: " + normalPath);
    }

    /** Runs a store operation, with icechunk's errors as n5's. */
    private static <T> T call(String normalPath, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (IcechunkException e) {
            throw new N5IOException("icechunk failed on " + normalPath + ": " + e.getMessage(), e);
        }
    }

    private static void run(String normalPath, Runnable operation) {
        call(normalPath, () -> {
            operation.run();
            return null;
        });
    }

    /** The groups and arrays of a read-only store; {@code metadata} is null for a writable store. */
    private static final class Nodes {
        private static final List<String> ARRAY_ENTRIES = Collections.unmodifiableList(Arrays.asList(METADATA, "c"));

        final Store store;
        final Map<String, String> metadata;
        private final Map<String, List<String>> children = new HashMap<>();

        Nodes(Store store, Map<String, String> metadata) {
            this.store = store;
            this.metadata = metadata;
            if (metadata == null) {
                return;
            }
            for (String path : metadata.keySet()) {
                if (!path.isEmpty()) {
                    int slash = path.lastIndexOf('/');
                    String parent = slash < 0 ? "" : path.substring(0, slash);
                    children.computeIfAbsent(parent, p -> new ArrayList<>()).add(path.substring(slash + 1));
                }
            }
        }

        /** The names of the groups and arrays directly below the node {@code path}. */
        List<String> children(String path) {
            return children.getOrDefault(path, Collections.emptyList());
        }

        /** What {@link Store#listDir} returns for the node {@code path}. */
        List<String> listDir(String path) {
            if (isArray(path)) {
                return ARRAY_ENTRIES;
            }
            List<String> entries = new ArrayList<>();
            entries.add(METADATA);
            entries.addAll(children(path));
            return entries;
        }

        private boolean isArray(String path) {
            if (children.containsKey(path)) {
                return false;
            }
            JsonElement type =
                    JsonParser.parseString(metadata.get(path)).getAsJsonObject().get("node_type");
            return type != null && type.getAsString().equals("array");
        }
    }

    /** Reads a {@code zarr.json} already in memory. */
    private static final class DocumentRead implements LazyRead {
        private final byte[] bytes;

        DocumentRead(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public ReadData materialize(long offset, long length) {
            int start = (int) offset;
            return ReadData.from(bytes, start, length < 0 ? bytes.length - start : (int) length);
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public void close() {}
    }

    /** Reads one key, fetching only the ranges n5 asks for. */
    private static final class StoreRead implements LazyRead {
        private final Store store;
        private final String key;
        private final String normalPath;

        StoreRead(Store store, String key, String normalPath) {
            this.store = store;
            this.key = key;
            this.normalPath = normalPath;
        }

        @Override
        public ReadData materialize(long offset, long length) {
            ByteRange range = length < 0 ? ByteRange.from(offset) : ByteRange.of(offset, offset + length);
            Optional<byte[]> bytes = call(normalPath, () -> store.get(key, range));
            return ReadData.from(bytes.orElseThrow(() -> noSuchKey(normalPath)));
        }

        @Override
        public long size() {
            return IcechunkKeyValueAccess.size(store, key, normalPath);
        }

        @Override
        public void close() {}
    }
}
