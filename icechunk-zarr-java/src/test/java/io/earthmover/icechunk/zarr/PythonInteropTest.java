package io.earthmover.icechunk.zarr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.DataType;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * icechunk-python writes a repository, Java reads it and commits on top, and icechunk-python checks the result. The
 * Python side runs through {@code uv}, which provides the latest icechunk and zarr releases.
 */
class PythonInteropTest {
    private static final Path SCRIPTS = Paths.get(System.getProperty("user.dir"))
            .getParent()
            .resolve("tests")
            .resolve("python");

    @Test
    void pythonWritesJavaReadsAndCommitsPythonChecks(@TempDir Path tmp) throws Exception {
        TestEnvironment.require(TestEnvironment.hasUv(), "uv");
        Path repoDir = tmp.resolve("repo");
        python("write_repo.py", repoDir);

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

        assertEquals("ok", python("check_repo.py", repoDir).trim());
    }

    private static String python(String script, Path repo) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "uv", "run", "--no-project", "--with", "icechunk", "--with", "zarr", "--with", "numpy", "python"));
        command.add(SCRIPTS.resolve(script).toString());
        command.add(repo.toString());
        Path stderr = Files.createTempFile("icechunk-python-", ".log");
        try {
            Process process =
                    new ProcessBuilder(command).redirectError(stderr.toFile()).start();
            String output = new String(process.getInputStream().readAllBytes(), UTF_8);
            int status = process.waitFor();
            assertEquals(0, status, () -> script + " failed:\n" + output + readQuietly(stderr));
            return output;
        } finally {
            Files.deleteIfExists(stderr);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return new String(Files.readAllBytes(file), UTF_8);
        } catch (IOException e) {
            return "(stderr unavailable: " + e + ")";
        }
    }
}
