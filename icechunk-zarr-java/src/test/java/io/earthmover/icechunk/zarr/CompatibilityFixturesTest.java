package io.earthmover.icechunk.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zarr.zarrjava.v3.Array;
import io.earthmover.icechunk.IcechunkException;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Store;
import io.earthmover.icechunk.Version;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reads the repositories icechunk keeps for its own format-compatibility test, {@code test_can_read_old.py}, and checks
 * the same facts that test checks, except those that need ancestry, diffs, or the virtual chunk it serves from MinIO.
 */
class CompatibilityFixturesTest {
    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"test-repo-v1", "test-repo-v2", "test-repo-v2-migrated"})
    void readsOldRepositories(String name) throws Exception {
        try (Storage storage = Storage.localFilesystem(TestEnvironment.fixture(name, tmp));
                Repository repo = Repository.open(storage)) {
            assertEquals(Set.of("main", "my-branch"), repo.listBranches());
            assertEquals(Set.of("it also works!", "it works!"), repo.listTags());
            assertThrows(IcechunkException.class, () -> repo.readonlySession(Version.tag("deleted")));

            try (Session session = repo.readonlySession(Version.branch("my-branch"))) {
                Store store = session.store();
                assertEquals(List.of("group1", "group2", "zarr.json"), sorted(store.listDir("")));
                assertEquals(List.of("big_chunks", "small_chunks", "zarr.json"), sorted(store.listDir("group1")));
                assertEquals(List.of("group3", "zarr.json"), sorted(store.listDir("group2")));
                assertEquals(List.of("c", "zarr.json"), sorted(store.listDir("group2/group3/group4/group5/inner")));

                IcechunkZarrStore zarr = new IcechunkZarrStore(session);

                Array inner = Array.open(zarr.resolve("group2/group3/group4/group5/inner"));
                for (float value : (float[]) inner.read().get1DJavaArray(ucar.ma2.DataType.FLOAT)) {
                    assertTrue(Float.isNaN(value), "inner has only fill values");
                }

                Array smallChunks = Array.open(zarr.resolve("group1/small_chunks"));
                assertArrayEquals(new byte[] {84, 84, 84, 84, 8}, (byte[])
                        smallChunks.read().get1DJavaArray(ucar.ma2.DataType.BYTE));

                // Chunk c/0/0 of big_chunks is virtual and served by MinIO in icechunk's test;
                // the rows below it are materialized.
                Array bigChunks = Array.open(zarr.resolve("group1/big_chunks"));
                float[] lowerHalf = (float[])
                        bigChunks.read(new long[] {5, 0}, new long[] {5, 10}).get1DJavaArray(ucar.ma2.DataType.FLOAT);
                float[] expected = new float[50];
                Arrays.fill(expected, 42.0f);
                assertArrayEquals(expected, lowerHalf);
            }
        }
    }

    private static List<String> sorted(List<String> keys) {
        String[] array = keys.toArray(new String[0]);
        Arrays.sort(array);
        return Arrays.asList(array);
    }
}
