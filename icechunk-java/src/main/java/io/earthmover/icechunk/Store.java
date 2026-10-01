package io.earthmover.icechunk;

import java.util.ArrayList;
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
 * Optional<byte[]> metadata = store.get("temperature/zarr.json");
 * Optional<byte[]> shardIndex = store.get("temperature/c/0/0", ByteRange.suffix(16));
 * }</pre>
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
        long h = handle();
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(
                NativeCall.runBytes(call -> Native.storeGet(call, h, key, range.kind(), range.a(), range.b())));
    }

    /**
     * Fetch several whole values concurrently. The result has one element per key, in order, empty where the key does
     * not exist.
     */
    public List<Optional<byte[]>> getMany(List<String> keys) {
        return getMany(keys, Collections.nCopies(keys.size(), ByteRange.all()));
    }

    /**
     * Fetch {@code ranges.get(i)} of {@code keys.get(i)} for every {@code i}, concurrently.
     *
     * @throws IllegalArgumentException if the lists differ in length
     */
    public List<Optional<byte[]>> getMany(List<String> keys, List<ByteRange> ranges) {
        if (keys.size() != ranges.size()) {
            throw new IllegalArgumentException("keys and ranges differ in length");
        }
        long h = handle();
        String[] keyArray = keys.toArray(new String[0]);
        long[] triples = new long[ranges.size() * 3];
        for (int i = 0; i < ranges.size(); i++) {
            ByteRange range = ranges.get(i);
            triples[3 * i] = range.kind();
            triples[3 * i + 1] = range.a();
            triples[3 * i + 2] = range.b();
        }
        byte[][] values = NativeCall.runBytesList(call -> Native.storeGetMany(call, h, keyArray, triples));
        List<Optional<byte[]>> result = new ArrayList<>(values.length);
        for (byte[] value : values) {
            result.add(Optional.ofNullable(value));
        }
        return Collections.unmodifiableList(result);
    }

    /** Store {@code value} at {@code key}, replacing any existing value. */
    public void set(String key, byte[] value) {
        long h = handle();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        NativeCall.runVoid(call -> Native.storeSet(call, h, key, value));
    }

    /** Store {@code value} at {@code key} unless the key already has a value. */
    public void setIfNotExists(String key, byte[] value) {
        long h = handle();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        NativeCall.runVoid(call -> Native.storeSetIfNotExists(call, h, key, value));
    }

    public boolean exists(String key) {
        long h = handle();
        return NativeCall.runBoolean(call -> Native.storeExists(call, h, key));
    }

    /** The size in bytes of the value at {@code key}, or empty if there is none. */
    public OptionalLong size(String key) {
        long h = handle();
        long size = NativeCall.runLong(call -> Native.storeSize(call, h, key));
        return size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /** Delete the value at {@code key}. Deleting a missing key is not an error. */
    public void delete(String key) {
        long h = handle();
        NativeCall.runVoid(call -> Native.storeDelete(call, h, key));
    }

    /** Delete every key under {@code prefix}. */
    public void deleteDir(String prefix) {
        long h = handle();
        NativeCall.runVoid(call -> Native.storeDeleteDir(call, h, prefix));
    }

    /** Returns true if no key starts with {@code prefix}. */
    public boolean isEmpty(String prefix) {
        long h = handle();
        return NativeCall.runBoolean(call -> Native.storeIsEmpty(call, h, prefix));
    }

    /** Every key in the store. */
    public List<String> list() {
        long h = handle();
        return NativeCall.runStrings(call -> Native.storeList(call, h, Native.LIST_ALL, ""));
    }

    /** Every key under {@code prefix}, as full keys. */
    public List<String> listPrefix(String prefix) {
        long h = handle();
        return NativeCall.runStrings(call -> Native.storeList(call, h, Native.LIST_PREFIX, prefix));
    }

    /**
     * The immediate children of {@code prefix}, relative to it: keys stored directly under it, and the names of the
     * groups and arrays one level down.
     */
    public List<String> listDir(String prefix) {
        long h = handle();
        return NativeCall.runStrings(call -> Native.storeList(call, h, Native.LIST_DIR, prefix));
    }

    public boolean isReadOnly() {
        long h = handle();
        return NativeCall.runBoolean(call -> Native.storeReadOnly(call, h));
    }
}
