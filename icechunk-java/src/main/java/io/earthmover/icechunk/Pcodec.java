package io.earthmover.icechunk;

import java.util.Objects;

/**
 * Decoding for the <a href="https://github.com/pcodec/pcodec">pcodec</a> numerical compression format, which has no
 * Java implementation. It needs only the native library, not a repository or session.
 */
public final class Pcodec {
    private Pcodec() {}

    /**
     * Decode a pcodec standalone file, the format numcodecs' {@code PCodec} writes, holding numbers of one Zarr data
     * type.
     *
     * @param encoded the encoded bytes
     * @param dtype the Zarr data type name of the numbers: {@code uint16}, {@code int16}, {@code uint32}, {@code int32},
     *     {@code uint64}, {@code int64}, {@code float32} or {@code float64}
     * @return the numbers as little-endian bytes
     * @throws IllegalArgumentException if pcodec does not support {@code dtype}
     * @throws IcechunkException if {@code encoded} is not a valid pcodec file of numbers of type {@code dtype}
     */
    public static byte[] decode(byte[] encoded, String dtype) {
        return Native.pcodecDecode(Objects.requireNonNull(encoded, "encoded"), Objects.requireNonNull(dtype, "dtype"));
    }
}
