package io.earthmover.icechunk.n5.codecs;

import io.earthmover.icechunk.Pcodec;
import java.nio.ByteOrder;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Exception.N5IOException;
import org.janelia.saalfeldlab.n5.codec.BlockCodec;
import org.janelia.saalfeldlab.n5.codec.BlockCodecInfo;
import org.janelia.saalfeldlab.n5.codec.DataCodec;
import org.janelia.saalfeldlab.n5.codec.DataCodecInfo;
import org.janelia.saalfeldlab.n5.codec.IdentityCodec;
import org.janelia.saalfeldlab.n5.codec.RawBlockCodecs;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.serialization.NameConfig;

/**
 * The {@code numcodecs.pcodec} array-to-bytes codec, which takes the place of {@code bytes} in an array's codecs. It
 * decodes through the icechunk native library and needs none of the codec's settings, which only steer encoding.
 * Writing is not supported.
 */
@NameConfig.Name(NumcodecsPcodec.TYPE)
public class NumcodecsPcodec implements BlockCodecInfo {
    private static final long serialVersionUID = 1L;

    public static final String TYPE = "numcodecs.pcodec";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public <T> BlockCodec<T> create(DataType dataType, int[] blockSize, DataCodecInfo... codecs) {
        return new Codec<>(dataType, blockSize, DataCodec.create(codecs));
    }

    private static final class Codec<T> implements BlockCodec<T> {
        private final String dtype;
        private final DataCodec codec;
        private final BlockCodec<T> raw;
        private final long rawSize;

        Codec(DataType dataType, int[] blockSize, DataCodec codec) {
            this.dtype = dataType.toString();
            this.codec = codec;
            this.raw = RawBlockCodecs.create(dataType, ByteOrder.LITTLE_ENDIAN, blockSize, new IdentityCodec());
            this.rawSize = raw.encodedSize(blockSize);
        }

        @Override
        public ReadData encode(DataBlock<T> dataBlock) {
            throw new UnsupportedOperationException("writing " + TYPE + " chunks is not supported");
        }

        @Override
        public DataBlock<T> decode(ReadData readData, long[] gridPosition) throws N5IOException {
            byte[] decoded = Pcodec.decode(codec.decode(readData).allBytes(), dtype);
            if (decoded.length != rawSize) {
                throw new N5IOException(
                        TYPE + " chunk holds " + decoded.length + " bytes of " + dtype + ", expected " + rawSize);
            }
            return raw.decode(ReadData.from(decoded), gridPosition);
        }
    }
}
