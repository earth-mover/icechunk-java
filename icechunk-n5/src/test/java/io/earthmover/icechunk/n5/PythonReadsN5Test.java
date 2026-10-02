package io.earthmover.icechunk.n5;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.TestEnvironment;
import java.nio.file.Path;
import org.janelia.saalfeldlab.n5.ByteArrayDataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.ShortArrayDataBlock;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * n5-zarr writes a plain and a sharded dataset through {@link IcechunkKeyValueAccess}, and zarr-python reads them
 * through icechunk-python. Each value is {@code x + width * y}, so a mismatch in data or in axis order shows, and the
 * group's name has a space, which n5 percent-encodes in the paths it composes.
 */
class PythonReadsN5Test {
    @Test
    void zarrPythonReadsWhatN5Wrote(@TempDir Path tmp) throws Exception {
        Path repoDir = tmp.resolve("repo");
        try (Storage storage = Storage.localFilesystem(repoDir);
                Repository repo = Repository.create(storage);
                Session session = repo.writableSession("main")) {
            N5Writer n5 = IcechunkKeyValueAccessTest.writer(session);
            n5.createGroup("em data");
            n5.setAttribute("em data", "units", "nm");

            DatasetAttributes raw = n5.createDataset(
                    "em data/raw", new long[] {10, 8}, new int[] {5, 4}, DataType.UINT16, new GzipCompression());
            for (int bx = 0; bx < 2; bx++) {
                for (int by = 0; by < 2; by++) {
                    int[] values = positions(new int[] {5, 4}, bx, by, 10);
                    short[] data = new short[values.length];
                    for (int i = 0; i < values.length; i++) {
                        data[i] = (short) values[i];
                    }
                    n5.writeChunk(
                            "em data/raw", raw, new ShortArrayDataBlock(new int[] {5, 4}, new long[] {bx, by}, data));
                }
            }

            DatasetAttributes labels = new ZarrV3DatasetAttributes(
                    new long[] {8, 8}, new int[] {4, 4}, new int[] {2, 2}, DataType.UINT8, new RawCompression());
            n5.createDataset("em data/labels", labels);
            for (int cx = 0; cx < 4; cx++) {
                for (int cy = 0; cy < 4; cy++) {
                    int[] values = positions(new int[] {2, 2}, cx, cy, 8);
                    byte[] data = new byte[values.length];
                    for (int i = 0; i < values.length; i++) {
                        data[i] = (byte) values[i];
                    }
                    n5.writeChunk(
                            "em data/labels",
                            labels,
                            new ByteArrayDataBlock(new int[] {2, 2}, new long[] {cx, cy}, data));
                }
            }
            session.commit("written by n5");
        }

        assertEquals(
                "ok",
                TestEnvironment.python("check_n5_repo.py", repoDir.toString()).trim());
    }

    /** {@code x + width * y} for each element of the block at grid position ({@code bx}, {@code by}), x fastest. */
    private static int[] positions(int[] size, int bx, int by, int width) {
        int[] values = new int[size[0] * size[1]];
        for (int j = 0; j < size[1]; j++) {
            for (int i = 0; i < size[0]; i++) {
                values[i + size[0] * j] = bx * size[0] + i + width * (by * size[1] + j);
            }
        }
        return values;
    }
}
