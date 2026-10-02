package io.earthmover.icechunk.n5;

import io.earthmover.icechunk.ByteRange;
import io.earthmover.icechunk.IcechunkException;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Store;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Errors from icechunk are thrown as {@link N5IOException}, with the {@link IcechunkException} as the cause. A path
 * that is not a key icechunk can hold, such as a group's path, is not a file; reading or writing it throws
 * {@link IllegalArgumentException}.
 */
public final class IcechunkKeyValueAccess implements KeyValueAccess {
    private static final String METADATA = "zarr.json";

    private final Supplier<Store> store;

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
        try {
            return !key.isEmpty() && call(normalPath, () -> store.get().exists(key));
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
        return VolatileReadData.from(new StoreRead(store.get(), key(normalPath), normalPath));
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
        return key.isEmpty() || call(normalPath, () -> store.get().exists(key + "/" + METADATA));
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
