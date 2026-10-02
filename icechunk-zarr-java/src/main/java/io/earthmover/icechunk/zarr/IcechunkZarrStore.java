package io.earthmover.icechunk.zarr;

import dev.zarr.zarrjava.store.Store;
import dev.zarr.zarrjava.store.StoreHandle;
import io.earthmover.icechunk.ByteRange;
import io.earthmover.icechunk.Session;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * A zarr-java {@link Store} that reads and writes through an icechunk {@link Session}.
 *
 * <pre>{@code
 * try (Session session = repo.readonlySession(Version.branch("main"))) {
 *     IcechunkZarrStore store = new IcechunkZarrStore(session);
 *     Array temperature = Array.open(store.resolve("temperature"));
 *     ucar.ma2.Array data = temperature.read();
 * }
 * }</pre>
 *
 * <p>The store does not own the session: close the session when done, and commit through the session to save writes.
 * An icechunk store only holds Zarr metadata documents and chunks, so writing an arbitrary key, such as a file that
 * is not a {@code zarr.json} or a chunk of an existing array, fails.
 */
public final class IcechunkZarrStore implements Store, Store.ListableStore {
    private final Session session;
    private final io.earthmover.icechunk.Store store;

    /**
     * A store over {@code session}'s {@link Session#store()}.
     *
     * @throws IllegalStateException if the session is closed
     */
    public IcechunkZarrStore(Session session) {
        this.session = Objects.requireNonNull(session, "session");
        this.store = session.store();
    }

    /** The session this store reads and writes through. */
    public Session session() {
        return session;
    }

    static String key(String[] keys) {
        StringBuilder key = new StringBuilder();
        for (String part : keys) {
            for (String segment : part.split("/", -1)) {
                if (!segment.isEmpty()) {
                    if (key.length() > 0) {
                        key.append('/');
                    }
                    key.append(segment);
                }
            }
        }
        return key.toString();
    }

    /**
     * Read the part of the value zarr-java's range arguments select, or empty if the key does not exist. {@code end}
     * is exclusive, and a negative {@code end} means the end of the value. A negative {@code start} counts back from
     * the end of the value; with a negative {@code end} that is a suffix read, which needs no size lookup.
     */
    private Optional<byte[]> read(String[] keys, long start, long end) {
        String key = key(keys);
        ByteRange range;
        if (start < 0 && end < 0) {
            range = ByteRange.suffix(-start);
        } else if (start < 0) {
            OptionalLong size = store.getSize(key);
            if (!size.isPresent()) {
                return Optional.empty();
            }
            long from = Math.max(0, size.getAsLong() + start);
            range = ByteRange.of(Math.min(from, end), end);
        } else if (end < 0) {
            range = ByteRange.from(start);
        } else {
            range = ByteRange.of(start, end);
        }
        return store.get(key, range);
    }

    @Override
    public boolean exists(String[] keys) {
        return store.exists(key(keys));
    }

    @Override
    public ByteBuffer get(String[] keys) {
        return get(keys, 0, -1);
    }

    @Override
    public ByteBuffer get(String[] keys, long start) {
        return get(keys, start, -1);
    }

    @Override
    public ByteBuffer get(String[] keys, long start, long end) {
        return read(keys, start, end).map(ByteBuffer::wrap).orElse(null);
    }

    @Override
    public void set(String[] keys, ByteBuffer bytes) {
        store.set(key(keys), bytes);
    }

    @Override
    public void delete(String[] keys) {
        store.delete(key(keys));
    }

    @Override
    public StoreHandle resolve(String... keys) {
        return new StoreHandle(this, keys);
    }

    @Override
    public InputStream getInputStream(String[] keys, long start, long end) {
        return read(keys, start, end).map(ByteArrayInputStream::new).orElse(null);
    }

    @Override
    public long getSize(String[] keys) {
        OptionalLong size = store.getSize(key(keys));
        return size.isPresent() ? size.getAsLong() : -1;
    }

    /**
     * Keys with data under {@code prefix}, relative to it. icechunk lists only under a group or array, so for any other
     * prefix, such as an array's {@code c} directory or a path that does not exist, this lists the nearest enclosing
     * node and keeps the keys under {@code prefix}.
     */
    @Override
    public Stream<String[]> list(String[] prefix) {
        String base = key(prefix);
        String node = base;
        while (!node.isEmpty() && !store.exists(node + "/zarr.json")) {
            int slash = node.lastIndexOf('/');
            node = slash < 0 ? "" : node.substring(0, slash);
        }
        List<String> keys = node.isEmpty() ? store.list() : store.listPrefix(node);
        if (base.isEmpty()) {
            return keys.stream().map(k -> k.split("/"));
        }
        String dir = base + "/";
        return keys.stream()
                .filter(k -> k.startsWith(dir))
                .map(k -> k.substring(dir.length()).split("/"));
    }

    @Override
    public Stream<String> listChildren(String[] prefix) {
        return store.listDir(key(prefix)).stream();
    }

    @Override
    public String toString() {
        return "IcechunkZarrStore";
    }
}
