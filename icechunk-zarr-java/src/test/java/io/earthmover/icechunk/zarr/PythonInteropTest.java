package io.earthmover.icechunk.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.DataType;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.TestEnvironment;
import io.earthmover.icechunk.Version;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * icechunk-python writes a repository, Java reads it and commits on top, and icechunk-python checks the result. The
 * Python side runs through {@code uv}, which provides the latest icechunk and zarr releases.
 */
class PythonInteropTest {
    @Test
    void pythonWritesJavaReadsAndCommitsPythonChecks(@TempDir Path tmp) throws Exception {
        Path repoDir = tmp.resolve("repo");
        TestEnvironment.python("write_repo.py", repoDir.toString());

        try (Storage storage = Storage.localFilesystem(repoDir);
                Repository repo = Repository.open(storage)) {
            try (Session session = repo.readonlySession(Version.tag("from-python"))) {
                IcechunkZarrStore store = new IcechunkZarrStore(session);

                double[] temperature = (double[])
                        Array.open(store.resolve("temperature")).read().get1DJavaArray(ucar.ma2.DataType.DOUBLE);
                double[] expected = new double[48];
                for (int i = 0; i < expected.length; i++) {
                    expected[i] = i / 2.0;
                }
                assertArrayEquals(expected, temperature);

                int[] counts =
                        (int[]) Array.open(store.resolve("counts")).read().get1DJavaArray(ucar.ma2.DataType.INT);
                assertArrayEquals(new int[] {0, 3, 6, 9, 12, 15, 18, 21, 24, 27}, counts);
            }

            try (Session session = repo.writableSession("main")) {
                IcechunkZarrStore store = new IcechunkZarrStore(session);
                short[] values = new short[12];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (short) (i - 6);
                }
                Array.create(
                                store.resolve("from_java"),
                                Array.metadataBuilder()
                                        .withShape(12)
                                        .withDataType(DataType.INT16)
                                        .withChunkShape(5)
                                        .withFillValue(0)
                                        .withCodecs(c -> c.withBytes().withZstd())
                                        .build())
                        .write(ucar.ma2.Array.factory(ucar.ma2.DataType.SHORT, new int[] {12}, values));
                session.commit("java wrote this");
            }
        }

        assertEquals(
                "ok",
                TestEnvironment.python("check_repo.py", repoDir.toString()).trim());
    }
}
