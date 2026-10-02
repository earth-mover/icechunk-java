package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static io.earthmover.icechunk.StoreTest.ARRAY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Snapshot expiration and garbage collection. */
class MaintenanceTest {
    /** Chunks are written as their own objects, so garbage collection has chunks to delete. */
    private static final RepositoryOptions NO_INLINING = RepositoryOptions.builder()
            .configJson("{\"inline_chunk_threshold_bytes\":0}")
            .build();

    private Storage storage;
    private Repository repo;
    private SnapshotId initial;

    @BeforeEach
    void open(@TempDir Path dir) {
        storage = Storage.localFilesystem(dir);
        repo = Repository.create(storage, NO_INLINING);
        initial = repo.lookupBranch("main");
    }

    @AfterEach
    void close() {
        repo.close();
        storage.close();
    }

    private SnapshotId commitChunk(String branch, int value) {
        try (Session session = repo.writableSession(branch)) {
            if (!session.store().exists("zarr.json")) {
                session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
                session.store().set("a/zarr.json", ByteBuffer.wrap(ARRAY));
            }
            session.store().set("a/c/0", ByteBuffer.wrap(chunk(value)));
            return session.commit("chunk " + value);
        }
    }

    private static byte[] chunk(int value) {
        return new byte[] {(byte) value, 1, 2, 3};
    }

    private Instant writtenAt(SnapshotId id) {
        return repo.lookupSnapshot(id).writtenAt();
    }

    private List<SnapshotId> history(String branch) {
        return repo.ancestry(Version.branch(branch)).stream()
                .map(SnapshotInfo::id)
                .collect(Collectors.toList());
    }

    @Test
    void expirationReleasesOldSnapshotsAndReparentsTheRest() {
        SnapshotId first = commitChunk("main", 1);
        SnapshotId second = commitChunk("main", 2);
        SnapshotId third = commitChunk("main", 3);

        ExpireResult result = repo.expireSnapshots(writtenAt(third));

        assertEquals(new HashSet<>(Arrays.asList(first, second)), result.releasedSnapshots());
        assertEquals(Collections.singleton(third), result.editedSnapshots());
        assertTrue(result.deletedBranches().isEmpty() && result.deletedTags().isEmpty());
        assertEquals(Arrays.asList(third, initial), history("main"));
    }

    @Test
    void expiredBranchesAndTagsAreDeletedOnlyWhenAsked() {
        SnapshotId first = commitChunk("main", 1);
        repo.createBranch("old", first);
        repo.createTag("v1", first);
        SnapshotId second = commitChunk("main", 2);
        Instant cutoff = writtenAt(second);

        ExpireResult kept = repo.expireSnapshots(cutoff);
        assertTrue(kept.releasedSnapshots().isEmpty());
        assertEquals(new HashSet<>(Arrays.asList("main", "old")), repo.listBranches());

        ExpireResult deleted = repo.expireSnapshots(
                cutoff,
                ExpireOptions.builder()
                        .deleteExpiredBranches(true)
                        .deleteExpiredTags(true)
                        .build());
        assertEquals(Collections.singleton(first), deleted.releasedSnapshots());
        assertEquals(Collections.singleton("old"), deleted.deletedBranches());
        assertEquals(Collections.singleton("v1"), deleted.deletedTags());
        assertEquals(Collections.singleton("main"), repo.listBranches());
        assertTrue(repo.listTags().isEmpty());
        assertThrows(IcechunkException.class, () -> repo.createTag("v1", second));
    }

    @Test
    void deletedTagNamesCannotBeReused() {
        SnapshotId first = commitChunk("main", 1);
        repo.createTag("t", first);
        repo.deleteTag("t");
        assertThrows(IcechunkException.class, () -> repo.createTag("t", first));
    }

    @Test
    void garbageCollectionDeletesReleasedSnapshotsAndTheirChunks() {
        commitChunk("main", 1);
        commitChunk("main", 2);
        SnapshotId third = commitChunk("main", 3);
        repo.expireSnapshots(writtenAt(third));
        Instant later = Instant.now().plusSeconds(60);

        GcOptions dryRunOptions =
                GcOptions.builder().deleteObjectsOlderThan(later).dryRun(true).build();
        GcSummary dryRun = repo.garbageCollect(dryRunOptions);
        assertEquals(2, dryRun.snapshotsDeleted());
        assertEquals(2, dryRun.chunksDeleted());
        assertEquals(2, dryRun.manifestsDeleted());
        assertEquals(0, dryRun.transactionLogsDeleted());
        assertTrue(dryRun.bytesDeleted() > 0);
        assertEquals(dryRun, repo.garbageCollect(dryRunOptions));

        assertEquals(dryRun, repo.garbageCollect(later));
        GcSummary nothingLeft = repo.garbageCollect(dryRunOptions);
        assertEquals(0, nothingLeft.snapshotsDeleted() + nothingLeft.chunksDeleted() + nothingLeft.bytesDeleted());
        try (Session session = repo.readonlySession(Version.branch("main"))) {
            assertArrayEquals(chunk(3), session.store().get("a/c/0").get());
        }
    }

    @Test
    void extraRootsKeepSnapshotsNoBranchLeadsTo() {
        SnapshotId first = commitChunk("main", 1);
        SnapshotId undone = commitChunk("main", 2);
        SnapshotId alsoUndone = commitChunk("main", 3);
        repo.resetBranch("main", first);
        Instant later = Instant.now().plusSeconds(60);

        GcSummary kept = repo.garbageCollect(GcOptions.builder()
                .deleteObjectsOlderThan(later)
                .extraRoots(Collections.singleton(undone))
                .build());
        assertEquals(1, kept.snapshotsDeleted());
        assertEquals(1, kept.transactionLogsDeleted());
        try (Session session = repo.readonlySession(Version.snapshot(undone))) {
            assertArrayEquals(chunk(2), session.store().get("a/c/0").get());
        }
        assertThrows(IcechunkException.class, () -> repo.readonlySession(Version.snapshot(alsoUndone)));

        assertEquals(1, repo.garbageCollect(later).snapshotsDeleted());
        assertThrows(IcechunkException.class, () -> repo.readonlySession(Version.snapshot(undone)));
    }

    @Test
    void extraRootsMustStillBeInTheRepository() {
        SnapshotId first = commitChunk("main", 1);
        SnapshotId second = commitChunk("main", 2);
        repo.expireSnapshots(writtenAt(second));

        GcOptions options = GcOptions.builder()
                .deleteObjectsOlderThan(Instant.now().plusSeconds(60))
                .extraRoots(Collections.singleton(first))
                .dryRun(true)
                .build();
        assertThrows(IcechunkException.class, () -> repo.garbageCollect(options));
    }

    @Test
    void kindsWithoutACutoffAreKept() {
        commitChunk("main", 1);
        SnapshotId second = commitChunk("main", 2);
        repo.expireSnapshots(writtenAt(second));

        GcSummary summary = repo.garbageCollect(GcOptions.builder()
                .deleteChunksOlderThan(Instant.now().plusSeconds(60))
                .build());

        assertEquals(1, summary.chunksDeleted());
        assertEquals(0, summary.snapshotsDeleted());
        assertEquals(0, summary.manifestsDeleted());
        assertEquals(0, summary.transactionLogsDeleted());
    }

    @Test
    void expirationWorksOnSpecVersion1(@TempDir Path dir) {
        try (Storage v1Storage = Storage.localFilesystem(dir);
                Repository v1 = Repository.create(
                        v1Storage, RepositoryOptions.builder().specVersion(1).build())) {
            SnapshotId[] ids = new SnapshotId[3];
            for (int i = 0; i < ids.length; i++) {
                try (Session session = v1.writableSession("main")) {
                    session.store().set("g" + i + "/zarr.json", ByteBuffer.wrap(GROUP));
                    ids[i] = session.commit("group " + i);
                }
            }

            // On spec version 1 an expired tip stays in its history: it is reported as released and edited.
            Instant afterTip = v1.lookupSnapshot(ids[2]).writtenAt().plusMillis(1);
            ExpireResult result = v1.expireSnapshots(afterTip);

            assertEquals(new HashSet<>(Arrays.asList(ids)), result.releasedSnapshots());
            assertEquals(Collections.singleton(ids[2]), result.editedSnapshots());
            assertEquals(ids[2], v1.lookupBranch("main"));
            assertEquals(2, v1.ancestry(Version.branch("main")).size());
        }
    }

    @Test
    void gcLimitsMustFitIcechunksTypes() {
        GcOptions.Builder builder = GcOptions.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.maxSnapshotsInMemory(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxSnapshotsInMemory(65536));
        assertThrows(IllegalArgumentException.class, () -> builder.maxConcurrentManifestFetches(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxCompressedManifestMemBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.deleteObjectsOlderThan(Instant.parse("+10000-01-01T00:00:00Z")));
        builder.maxSnapshotsInMemory(65535).maxConcurrentManifestFetches(1);
    }
}
