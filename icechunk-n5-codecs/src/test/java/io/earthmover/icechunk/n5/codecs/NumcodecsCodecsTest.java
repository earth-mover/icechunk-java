package io.earthmover.icechunk.n5.codecs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.GsonBuilder;
import io.earthmover.icechunk.IcechunkException;
import io.earthmover.icechunk.Pcodec;
import io.earthmover.icechunk.TestEnvironment;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.FileSystemKeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * zarr-python writes arrays with numcodecs codecs to the local filesystem, and n5-zarr reads them back, finding the
 * codecs through the annotation index this module's jar carries. {@code write_codec_arrays.py} describes the arrays.
 */
class NumcodecsCodecsTest {
    private static final int WIDTH = 10;
    private static final int HEIGHT = 7;
    private static final int[] CHUNK = {4, 3};

    @TempDir
    static Path tmp;

    private static N5Reader n5;

    @BeforeAll
    static void writeArrays() throws Exception {
        TestEnvironment.pythonWith(Collections.singletonList("pcodec"), "write_codec_arrays.py", tmp.toString());
        n5 = new ZarrV3KeyValueReader(new FileSystemKeyValueAccess(), tmp.toString(), new GsonBuilder(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uint16", "int16", "uint32", "int32", "uint64", "int64", "float32", "float64"})
    void readsPcodec(String dtype) {
        DatasetAttributes attributes = n5.getDatasetAttributes("pcodec/" + dtype);
        assertInstanceOf(NumcodecsPcodec.class, attributes.getBlockCodecInfo());
        assertEquals(DataType.fromString(dtype), attributes.getDataType());
        assertArray("pcodec/" + dtype, attributes);
    }

    @Test
    void readsPcodecFollowedByZlib() {
        DatasetAttributes attributes = n5.getDatasetAttributes("pcodec_zlib/float32");
        assertInstanceOf(NumcodecsZlib.class, attributes.getDataCodecInfos()[0]);
        assertArray("pcodec_zlib/float32", attributes);
    }

    @Test
    void readsZlib() {
        DatasetAttributes attributes = n5.getDatasetAttributes("zlib/uint16");
        assertInstanceOf(NumcodecsZlib.class, attributes.getDataCodecInfos()[0]);
        assertArray("zlib/uint16", attributes);
    }

    @Test
    void pcodecRejectsTheWrongDataType() throws Exception {
        byte[] chunk = Files.readAllBytes(tmp.resolve("pcodec/float32/c/0/0"));
        assertEquals(4 * 12, Pcodec.decode(chunk, "float32").length);
        assertThrows(IcechunkException.class, () -> Pcodec.decode(chunk, "int32"));
        assertThrows(IllegalArgumentException.class, () -> Pcodec.decode(chunk, "uint8"));
        assertThrows(IcechunkException.class, () -> Pcodec.decode(new byte[] {1, 2, 3}, "float32"));
    }

    /** Check every in-bounds element of every chunk; n5 orders dimensions x first. */
    private static void assertArray(String path, DatasetAttributes attributes) {
        assertArrayEquals(new long[] {WIDTH, HEIGHT}, attributes.getDimensions());
        assertArrayEquals(CHUNK, attributes.getBlockSize());
        DataType type = attributes.getDataType();
        for (int gx = 0; gx * CHUNK[0] < WIDTH; gx++) {
            for (int gy = 0; gy * CHUNK[1] < HEIGHT; gy++) {
                DataBlock<?> chunk = n5.readChunk(path, attributes, gx, gy);
                Object data = chunk.getData();
                for (int j = 0; j < CHUNK[1]; j++) {
                    for (int i = 0; i < CHUNK[0]; i++) {
                        int x = gx * CHUNK[0] + i;
                        int y = gy * CHUNK[1] + j;
                        if (x < WIDTH && y < HEIGHT) {
                            int ramp = x + WIDTH * y;
                            double actual = ((Number) Array.get(data, i + CHUNK[0] * j)).doubleValue();
                            assertEquals(expected(type, ramp), actual, path + " at (" + x + ", " + y + ")");
                        }
                    }
                }
            }
        }
    }

    private static double expected(DataType type, int ramp) {
        switch (type) {
            case FLOAT32:
            case FLOAT64:
                return ramp * 0.25 - 3;
            case INT16:
            case INT32:
            case INT64:
                return ramp - 30;
            default:
                return ramp;
        }
    }
}
