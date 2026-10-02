package io.earthmover.icechunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * The result documents the native layer writes. The same strings are produced by the tests in
 * {@code native/src/results.rs}; a change on one side must be made on the other.
 */
class ResultsContractTest {
    @Test
    void snapshotInfo() {
        SnapshotInfo info = SnapshotInfo.fromJson(
                JsonReader.readObject(
                        "{\"id\":\"1CECHNKREP0F1RSTCMT0\",\"parent_id\":\"1CECHNKREP0F1RSTCMT0\","
                                + "\"flushed_at\":\"2026-01-02T03:04:05.000000Z\",\"message\":\"m\",\"metadata\":{\"k\":[1,\"x\"]}}"));
        SnapshotId id = SnapshotId.of("1CECHNKREP0F1RSTCMT0");
        assertEquals(id, info.id());
        assertEquals(id, info.parentId().get());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), info.writtenAt());
        assertEquals("m", info.message());
        assertEquals(Collections.singletonMap("k", Arrays.asList(1L, "x")), info.metadata());
    }

    @Test
    void diff() {
        Diff diff = Diff.fromJson("{\"new_groups\":[\"/ng\"],\"new_arrays\":[\"/na\"],\"deleted_groups\":[\"/dg\"],"
                + "\"deleted_arrays\":[\"/da\"],\"updated_groups\":[\"/ug\"],\"updated_arrays\":[\"/ua\"],"
                + "\"updated_chunks\":{\"/ua\":[[0,1],[2,3]]},\"moved_nodes\":[{\"from\":\"/a\",\"to\":\"/b\"}]}");
        assertEquals(Collections.singleton("/ng"), diff.newGroups());
        assertEquals(Collections.singleton("/na"), diff.newArrays());
        assertEquals(Collections.singleton("/dg"), diff.deletedGroups());
        assertEquals(Collections.singleton("/da"), diff.deletedArrays());
        assertEquals(Collections.singleton("/ug"), diff.updatedGroups());
        assertEquals(Collections.singleton("/ua"), diff.updatedArrays());
        assertEquals(
                Collections.singletonMap("/ua", Arrays.asList(Arrays.asList(0L, 1L), Arrays.asList(2L, 3L))),
                diff.updatedChunks());
        assertEquals(1, diff.movedNodes().size());
        assertEquals("/a", diff.movedNodes().get(0).from());
        assertEquals("/b", diff.movedNodes().get(0).to());
    }

    @Test
    void missingFieldsNameTheField() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Diff.fromJson("{\"new_groups\":[]}"));
        assertEquals("native result field new_arrays is missing or not a list", e.getMessage());
    }
}
