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
     * Translate zarr-java's range convention. {@code end} is exclusive, and a negative {@code end} means the end of the
     * value. A negative {@code start} counts back from the end of the value; with a negative {@code end} that is a
     * suffix read, which needs no size lookup.
     */
    private ByteRange range(String key, long start, long end) {
        if (start < 0 && end < 0) {
            return ByteRange.suffix(-start);
        }
        if (start < 0) {
            OptionalLong size = store.size(key);
            if (!size.isPresent()) {
                return null;
            }
            return ByteRange.of(Math.max(0, size.getAsLong() + start), end);
        }
        if (end < 0) {
            return start == 0 ? ByteRange.all() : ByteRange.from(start);
        }
        return ByteRange.of(start, end);
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

    /** Returns a read-only buffer over icechunk's memory; see {@link io.earthmover.icechunk.Store#getBuffer}. */
    @Override
    public ByteBuffer get(String[] keys, long start, long end) {
        String key = key(keys);
        ByteRange range = range(key, start, end);
        if (range == null) {
            return null;
        }
        return store.getBuffer(key, range).orElse(null);
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
        String key = key(keys);
        ByteRange range = range(key, start, end);
        if (range == null) {
            return null;
        }
        Optional<byte[]> value = store.get(key, range);
        return value.map(ByteArrayInputStream::new).orElse(null);
    }

    @Override
    public long getSize(String[] keys) {
        OptionalLong size = store.size(key(keys));
        return size.isPresent() ? size.getAsLong() : -1;
    }

    @Override
    public Stream<String[]> list(String[] prefix) {
        String base = key(prefix);
        List<String> keys = base.isEmpty() ? store.list() : store.listPrefix(base);
        int strip = base.isEmpty() ? 0 : base.length() + 1;
        return keys.stream()
                .filter(k -> base.isEmpty() || k.startsWith(base + "/"))
                .map(k -> k.substring(strip).split("/"));
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
