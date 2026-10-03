package io.earthmover.icechunk;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The Zarr key/value store of a {@link Session}.
 *
 * <p>Keys are Zarr v3 keys relative to the hierarchy root, such as {@code zarr.json}, {@code group/array/zarr.json}
 * or {@code group/array/c/0/0}. A store belongs to its session and closes with it; it is safe to use from several
 * threads.
 *
 * <pre>{@code
 * Store store = session.store();
 * Optional<byte[]> metadata = store.get("temperature/zarr.json");
 * Optional<byte[]> shardIndex = store.get("temperature/c/0/0", ByteRange.suffix(16));
 * store.set("temperature/c/0/1", ByteBuffer.wrap(encodedChunk));
 * }</pre>
 *
 * <p>Reads copy the value into a new array and free icechunk's copy before returning, so memory stays bounded when
 * streaming through many values. Writes take a {@link ByteBuffer}. A heap buffer's bytes are copied once into
 * icechunk. A direct buffer of more than 64 KiB is read in place instead, and icechunk may keep using it after
 * {@code set} returns: in-memory storage keeps it for as long as the storage lives, and a value below the
 * repository's inline chunk threshold stays in the session until commit. Treat a direct buffer passed to
 * {@code set} as handed over, and do not modify it afterwards.
 *
 * <p>A key is the {@code zarr.json} of a group or array, or the key of one of an array's chunks. A string icechunk
 * cannot parse as a key, such as a group's path, throws {@link IllegalArgumentException} where a key is expected.
 * Changes through a read-only session throw {@link IcechunkException}.
 */
public final class Store extends NativeHandle {
    Store(long handle) {
        super(handle);
    }

    /** The value stored at {@code key}, or empty if there is none. */
    public Optional<byte[]> get(String key) {
        return get(key, ByteRange.all());
    }

    /** The {@code range} of the value stored at {@code key}, or empty if there is none. */
    public Optional<byte[]> get(String key, ByteRange range) {
        Objects.requireNonNull(key, "key");
        try {
            return Optional.ofNullable(Native.storeGet(handle(), key, range.kind(), range.a(), range.b()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * Fetch several whole values concurrently. The result has one element per key, in order, empty where the key does
     * not exist. Use this rather than one {@link #get} per key when reading from object storage, where each request
     * pays a network round trip.
     */
    public List<Optional<byte[]>> getPartialValues(List<String> keys) {
        return getPartialValues(keys, Collections.nCopies(keys.size(), ByteRange.all()));
    }

    /**
     * Fetch {@code ranges.get(i)} of {@code keys.get(i)} for every {@code i}, concurrently.
     *
     * @throws IllegalArgumentException if the lists differ in length
     */
    public List<Optional<byte[]>> getPartialValues(List<String> keys, List<ByteRange> ranges) {
        if (keys.size() != ranges.size()) {
            throw new IllegalArgumentException("keys and ranges differ in length");
        }
        long[] triples = new long[ranges.size() * 3];
        for (int i = 0; i < ranges.size(); i++) {
            ByteRange range = ranges.get(i);
            triples[3 * i] = range.kind();
            triples[3 * i + 1] = range.a();
            triples[3 * i + 2] = range.b();
        }
        byte[][] values;
        try {
            values = Native.storeGetPartialValues(handle(), keys.toArray(new String[0]), triples);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        List<Optional<byte[]>> result = new ArrayList<>(values.length);
        for (byte[] value : values) {
            result.add(Optional.ofNullable(value));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Store the remaining bytes of {@code value} at {@code key}, replacing any existing value. The buffer's position
     * is not changed. See the class description for when a direct buffer is read in place.
     *
     * @throws IcechunkException if the session is read-only, or {@code key} is a chunk key and there is no array at
     *     its path
     */
    public void set(String key, ByteBuffer value) {
        write(key, value, false);
    }

    /** As {@link #set}, unless the key already has a value. */
    public void setIfNotExists(String key, ByteBuffer value) {
        write(key, value, true);
    }

    private void write(String key, ByteBuffer value, boolean onlyIfNew) {
        Objects.requireNonNull(key, "key");
        try {
            if (value.isDirect()) {
                Native.storeSetBuffer(handle(), key, value, value.position(), value.remaining(), onlyIfNew);
            } else if (value.hasArray()) {
                Native.storeSet(
                        handle(),
                        key,
                        value.array(),
                        value.arrayOffset() + value.position(),
                        value.remaining(),
                        onlyIfNew);
            } else {
                byte[] copy = new byte[value.remaining()];
                value.duplicate().get(copy);
                Native.storeSet(handle(), key, copy, 0, copy.length, onlyIfNew);
            }
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Returns true if {@code key} has a value. */
    public boolean exists(String key) {
        try {
            return Native.storeExists(handle(), key);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The size in bytes of the value at {@code key}, or empty if there is none. */
    public OptionalLong getSize(String key) {
        long size;
        try {
            size = Native.storeGetSize(handle(), key);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        return size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /** Delete the value at {@code key}. Deleting a missing key is not an error. */
    public void delete(String key) {
        try {
            Native.storeDelete(handle(), key);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Delete every key under {@code prefix}. */
    public void deleteDir(String prefix) {
        try {
            Native.storeDeleteDir(handle(), prefix);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * The total size in bytes of the metadata and chunks of the group or array at {@code prefix} and everything below
     * it. An empty prefix sizes the whole store.
     *
     * <p>The total is not a snapshot: with concurrent writes it may or may not include keys written or deleted while
     * it runs.
     *
     * @throws IcechunkException if {@code prefix} is not a group or array
     */
    public long getSizePrefix(String prefix) {
        try {
            return Native.storeGetSizePrefix(handle(), prefix);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Delete every key in the store. */
    public void clear() {
        try {
            Native.storeClear(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Returns true if no key starts with {@code prefix}. */
    public boolean isEmpty(String prefix) {
        try {
            return Native.storeIsEmpty(handle(), prefix);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Every key in the store. */
    public List<String> list() {
        return list(Native.LIST_ALL, "");
    }

    /**
     * Every key under the group or array {@code prefix}, as full keys.
     *
     * @throws IcechunkException if {@code prefix} is not a group or array
     */
    public List<String> listPrefix(String prefix) {
        return list(Native.LIST_PREFIX, prefix);
    }

    /**
     * The immediate children of {@code prefix}, relative to it: keys stored directly under it, and the names of the
     * groups and arrays one level down.
     */
    public List<String> listDir(String prefix) {
        return list(Native.LIST_DIR, prefix);
    }

    private List<String> list(int mode, String prefix) {
        try {
            return Collections.unmodifiableList(Arrays.asList(Native.storeList(handle(), mode, prefix)));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * Every group and array, by path, with its {@code zarr.json} document. Paths have no leading slash, and the root
     * is {@code ""}. The documents come from the session's snapshot and changes, so unlike {@link #list} this reads no
     * manifests. A read-only session lists them in path order.
     */
    public Map<String, String> listNodes() {
        String[] entries;
        try {
            entries = Native.storeListNodes(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        Map<String, String> nodes = new LinkedHashMap<>(entries.length);
        for (int i = 0; i < entries.length; i += 2) {
            nodes.put(entries[i], entries[i + 1]);
        }
        return Collections.unmodifiableMap(nodes);
    }

    /** Returns true if the store's session cannot write. */
    public boolean isReadOnly() {
        try {
            return Native.storeReadOnly(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }
}
