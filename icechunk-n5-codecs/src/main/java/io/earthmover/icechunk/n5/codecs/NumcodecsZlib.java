package io.earthmover.icechunk.n5.codecs;

import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.serialization.NameConfig;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3Compressor;

/** The {@code numcodecs.zlib} bytes-to-bytes codec: a zlib stream, as Python's {@code zlib.compress} writes. */
@NameConfig.Name(NumcodecsZlib.TYPE)
public class NumcodecsZlib implements ZarrV3Compressor {
    private static final long serialVersionUID = 1L;

    public static final String TYPE = "numcodecs.zlib";

    /** numcodecs' default. */
    private static final int DEFAULT_LEVEL = 1;

    @NameConfig.Parameter(optional = true)
    private final int level;

    public NumcodecsZlib() {
        this(DEFAULT_LEVEL);
    }

    public NumcodecsZlib(int level) {
        this.level = level;
    }

    @Override
    public GzipCompression getCompression() {
        return new GzipCompression(level, true);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
