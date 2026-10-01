package io.earthmover.icechunk;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * Optional<ByteBuffer> chunk = store.getBuffer("temperature/c/0/0");
 * Optional<byte[]> metadata = store.get("temperature/zarr.json");
 * }</pre>
 *
 * <h2>Memory</h2>
 *
 * <p>{@link #getBuffer} and {@link #getManyBuffers} return read-only direct buffers over icechunk's own memory, with no
 * copy. That memory is released when the buffer, and every slice or duplicate of it, becomes unreachable and is
 * collected. {@link #get} and {@link #getMany} copy into a new {@code byte[]}, which suits small values and callers
 * that need an array.
 *
 * <p>{@link #set(String, ByteBuffer)} with a direct buffer of more than 64 KiB lets icechunk read the buffer in place.
 * Do not modify that region afterwards: icechunk may still hold it, for example until a commit when the storage is in
 * memory. Heap buffers and arrays are copied.
 */
public final class Store extends NativeHandle {
    Store(long handle) {
        super(handle);
    }

    /** The value stored at {@code key}, copied into a new array, or empty if there is none. */
    public Optional<byte[]> get(String key) {
        return get(key, ByteRange.all());
    }

    /** The {@code range} of the value stored at {@code key}, copied into a new array, or empty if there is none. */
    public Optional<byte[]> get(String key, ByteRange range) {
        Objects.requireNonNull(key, "key");
        try {
            return Optional.ofNullable(Native.storeGet(handle(), key, range.kind(), range.a(), range.b()));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The value stored at {@code key} as a read-only buffer over icechunk's memory, or empty if there is none. */
    public Optional<ByteBuffer> getBuffer(String key) {
        return getBuffer(key, ByteRange.all());
    }

    /** The {@code range} of the value at {@code key} as a read-only buffer over icechunk's memory. */
    public Optional<ByteBuffer> getBuffer(String key, ByteRange range) {
        Objects.requireNonNull(key, "key");
        long[] out = new long[2];
        ByteBuffer buffer;
        try {
            buffer = Native.storeGetBuffer(handle(), key, range.kind(), range.a(), range.b(), out);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (buffer == null) {
            return Optional.empty();
        }
        ByteBuffer view = NativeBuffers.adopt(buffer, out[0]);
        NativeBuffers.afterLend(out[1]);
        return Optional.of(view);
    }

    /**
     * Fetch several whole values concurrently, copied into new arrays. The result has one element per key, in order,
     * empty where the key does not exist.
     */
    public List<Optional<byte[]>> getMany(List<String> keys) {
        return getMany(keys, Collections.nCopies(keys.size(), ByteRange.all()));
    }

    /**
     * Fetch {@code ranges.get(i)} of {@code keys.get(i)} for every {@code i}, concurrently, copied into new arrays.
     *
     * @throws IllegalArgumentException if the lists differ in length
     */
    public List<Optional<byte[]>> getMany(List<String> keys, List<ByteRange> ranges) {
        byte[][] values;
        try {
            values = Native.storeGetMany(handle(), keys.toArray(new String[0]), triples(keys, ranges));
        } finally {
            Reference.reachabilityFence(this);
        }
        List<Optional<byte[]>> result = new ArrayList<>(values.length);
        for (byte[] value : values) {
            result.add(Optional.ofNullable(value));
        }
        return Collections.unmodifiableList(result);
    }

    /** As {@link #getMany(List)}, returning read-only buffers over icechunk's memory. */
    public List<Optional<ByteBuffer>> getManyBuffers(List<String> keys) {
        return getManyBuffers(keys, Collections.nCopies(keys.size(), ByteRange.all()));
    }

    /** As {@link #getMany(List, List)}, returning read-only buffers over icechunk's memory. */
    public List<Optional<ByteBuffer>> getManyBuffers(List<String> keys, List<ByteRange> ranges) {
        long[] out = new long[keys.size() + 1];
        ByteBuffer[] buffers;
        try {
            buffers = Native.storeGetManyBuffers(handle(), keys.toArray(new String[0]), triples(keys, ranges), out);
        } finally {
            Reference.reachabilityFence(this);
        }
        List<Optional<ByteBuffer>> result = new ArrayList<>(buffers.length);
        for (int i = 0; i < buffers.length; i++) {
            result.add(buffers[i] == null ? Optional.empty() : Optional.of(NativeBuffers.adopt(buffers[i], out[i])));
        }
        NativeBuffers.afterLend(out[buffers.length]);
        return Collections.unmodifiableList(result);
    }

    private static long[] triples(List<String> keys, List<ByteRange> ranges) {
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
        return triples;
    }

    /** Store a copy of {@code value} at {@code key}, replacing any existing value. */
    public void set(String key, byte[] value) {
        write(key, value, 0, value.length, false);
    }

    /**
     * Store the remaining bytes of {@code value} at {@code key}, replacing any existing value. The buffer's position
     * is not changed. See the class description for when a direct buffer is read in place.
     */
    public void set(String key, ByteBuffer value) {
        write(key, value, false);
    }

    /** Store a copy of {@code value} at {@code key} unless the key already has a value. */
    public void setIfNotExists(String key, byte[] value) {
        write(key, value, 0, value.length, true);
    }

    /** As {@link #set(String, ByteBuffer)}, unless the key already has a value. */
    public void setIfNotExists(String key, ByteBuffer value) {
        write(key, value, true);
    }

    private void write(String key, byte[] value, int offset, int length, boolean onlyIfNew) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        try {
            Native.storeSet(handle(), key, value, offset, length, onlyIfNew);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private void write(String key, ByteBuffer value, boolean onlyIfNew) {
        Objects.requireNonNull(key, "key");
        if (value.isDirect()) {
            try {
                Native.storeSetBuffer(handle(), key, value, value.position(), value.remaining(), onlyIfNew);
            } finally {
                Reference.reachabilityFence(this);
            }
        } else if (value.hasArray()) {
            write(key, value.array(), value.arrayOffset() + value.position(), value.remaining(), onlyIfNew);
        } else {
            byte[] copy = new byte[value.remaining()];
            value.duplicate().get(copy);
            write(key, copy, 0, copy.length, onlyIfNew);
        }
    }

    public boolean exists(String key) {
        try {
            return Native.storeExists(handle(), key);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The size in bytes of the value at {@code key}, or empty if there is none. */
    public OptionalLong size(String key) {
        long size;
        try {
            size = Native.storeSize(handle(), key);
        } finally {
            Reference.reachabilityFence(this);
        }
        return size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /** Delete the value at {@code key}. Deleting a missing key is not an error. */
    public void delete(String key) {
        try {
            Native.storeDelete(handle(), key);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Delete every key under {@code prefix}. */
    public void deleteDir(String prefix) {
        try {
            Native.storeDeleteDir(handle(), prefix);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Returns true if no key starts with {@code prefix}. */
    public boolean isEmpty(String prefix) {
        try {
            return Native.storeIsEmpty(handle(), prefix);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Every key in the store. */
    public List<String> list() {
        return list(Native.LIST_ALL, "");
    }

    /** Every key under {@code prefix}, as full keys. */
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
            Reference.reachabilityFence(this);
        }
    }

    public boolean isReadOnly() {
        try {
            return Native.storeReadOnly(handle());
        } finally {
            Reference.reachabilityFence(this);
        }
    }
}
