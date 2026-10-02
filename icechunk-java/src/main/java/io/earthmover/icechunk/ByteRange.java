package io.earthmover.icechunk;

/** The part of a stored value to read. */
public final class ByteRange {
    private static final ByteRange ALL = new ByteRange(Native.RANGE_FROM, 0, 0);

    private final long kind;
    private final long a;
    private final long b;

    private ByteRange(long kind, long a, long b) {
        this.kind = kind;
        this.a = a;
        this.b = b;
    }

    /** The whole value; the same as {@code from(0)}. */
    public static ByteRange all() {
        return ALL;
    }

    /**
     * Bytes {@code start} (inclusive) to {@code end} (exclusive).
     *
     * @throws IllegalArgumentException if {@code start} is negative or {@code end} is before {@code start}
     */
    public static ByteRange of(long start, long end) {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid byte range [" + start + ", " + end + ")");
        }
        return new ByteRange(Native.RANGE_BOUNDED, start, end);
    }

    /** Everything from byte {@code offset} to the end. */
    public static ByteRange from(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        return new ByteRange(Native.RANGE_FROM, offset, 0);
    }

    /** The last {@code length} bytes. */
    public static ByteRange suffix(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
        return new ByteRange(Native.RANGE_SUFFIX, length, 0);
    }

    long kind() {
        return kind;
    }

    long a() {
        return a;
    }

    long b() {
        return b;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ByteRange)) {
            return false;
        }
        ByteRange that = (ByteRange) other;
        return kind == that.kind && a == that.a && b == that.b;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(kind) * 961 + Long.hashCode(a) * 31 + Long.hashCode(b);
    }

    @Override
    public String toString() {
        if (kind == Native.RANGE_FROM && a == 0) {
            return "all";
        } else if (kind == Native.RANGE_BOUNDED) {
            return "[" + a + ", " + b + ")";
        } else if (kind == Native.RANGE_FROM) {
            return "[" + a + ", end)";
        }
        return "last " + a;
    }
}
