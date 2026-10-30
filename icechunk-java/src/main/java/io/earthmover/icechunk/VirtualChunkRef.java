package io.earthmover.icechunk;

import java.time.Instant;
import java.util.Objects;

/**
 * A chunk stored as a byte range of an object outside the repository, such as part of a NetCDF, HDF5 or TIFF file.
 * Write one with {@link Store#setVirtualRef} or {@link Store#setVirtualRefs}.
 *
 * <p>The location's URL must start with the prefix of a {@link VirtualChunkContainer} in the repository's
 * configuration, and readers must be granted access with {@link RepositoryOptions.Builder#authorizeVirtualChunkAccess}.
 * A checksum, an ETag or a last-modified time, makes reads fail if the object has changed since the reference was
 * written, instead of returning the wrong bytes.
 *
 * <pre>{@code
 * VirtualChunkRef.of("s3://bucket/data/file.nc", 4096, 1024).withEtag("\"8f0e...\"");
 * }</pre>
 */
public final class VirtualChunkRef {
    private final String location;
    private final long offset;
    private final long length;
    private final String etag;
    private final Instant lastModified;

    private VirtualChunkRef(String location, long offset, long length, String etag, Instant lastModified) {
        this.location = location;
        this.offset = offset;
        this.length = length;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    /** {@code length} bytes at {@code offset} of the object at the URL {@code location}, with no checksum. */
    public static VirtualChunkRef of(String location, long offset, long length) {
        Objects.requireNonNull(location, "location");
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must not be negative");
        }
        return new VirtualChunkRef(location, offset, length, null, null);
    }

    /** This reference, valid only while the object's ETag is {@code etag}. Replaces any last-modified checksum. */
    public VirtualChunkRef withEtag(String etag) {
        return new VirtualChunkRef(location, offset, length, Objects.requireNonNull(etag, "etag"), null);
    }

    /**
     * This reference, valid only while the object has not been modified after {@code lastModified}, which is kept to
     * the second. Replaces any ETag checksum.
     */
    public VirtualChunkRef withLastModified(Instant lastModified) {
        long seconds = Objects.requireNonNull(lastModified, "lastModified").getEpochSecond();
        if (seconds < 0 || seconds > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("lastModified must be between 1970 and 2106");
        }
        return new VirtualChunkRef(location, offset, length, null, Instant.ofEpochSecond(seconds));
    }

    public String location() {
        return location;
    }

    public long offset() {
        return offset;
    }

    public long length() {
        return length;
    }

    Json toJson() {
        return Json.object()
                .put("location", location)
                .put("offset", offset)
                .put("length", length)
                .put("etag", etag)
                .put("last_modified", lastModified == null ? null : lastModified.getEpochSecond());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VirtualChunkRef)) {
            return false;
        }
        VirtualChunkRef that = (VirtualChunkRef) other;
        return location.equals(that.location)
                && offset == that.offset
                && length == that.length
                && Objects.equals(etag, that.etag)
                && Objects.equals(lastModified, that.lastModified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location, offset, length, etag, lastModified);
    }

    @Override
    public String toString() {
        return "VirtualChunkRef(" + location + ", " + offset + ", " + length + ")";
    }
}
