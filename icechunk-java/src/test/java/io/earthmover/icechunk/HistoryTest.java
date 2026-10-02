package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static io.earthmover.icechunk.StoreTest.ARRAY;
import static io.earthmover.icechunk.StoreTest.CHUNK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HistoryTest {
    private Storage storage;
    private Repository repo;
    private SnapshotId initial;

    @BeforeEach
    void open() {
        storage = Storage.inMemory();
        repo = Repository.create(storage);
        initial = repo.lookupBranch("main");
    }

    @AfterEach
    void close() {
        repo.close();
        storage.close();
    }

    private SnapshotId commit(String key, byte[] value, String message) {
        try (Session session = repo.writableSession("main")) {
            session.store().set(key, ByteBuffer.wrap(value));
            return session.commit(message);
        }
    }

    @Test
    void metadataRoundTrips() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("list", Arrays.asList(1, "two", null, 2.5));
        nested.put("flag", true);
        SnapshotId id;
        try (Session session = repo.writableSession("main")) {
            session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
            id = session.commit(
                    "with metadata",
                    CommitOptions.builder()
                            .metadata("author", "ian")
                            .metadata("count", 3)
                            .metadata("big", new BigInteger("18446744073709551615"))
                            .metadata("nested", nested)
                            .build());
        }

        Map<String, Object> metadata = repo.lookupSnapshot(id).metadata();
        assertEquals("ian", metadata.get("author"));
        assertEquals(3L, metadata.get("count"));
        assertEquals(new BigInteger("18446744073709551615"), metadata.get("big"));
        Map<String, Object> expectedNested = new LinkedHashMap<>();
        expectedNested.put("list", Arrays.asList(1L, "two", null, 2.5));
        expectedNested.put("flag", true);
        assertEquals(expectedNested, metadata.get("nested"));
        assertEquals(metadata, repo.ancestry(Version.branch("main")).get(0).metadata());
        // icechunk marks the first snapshot in metadata of its own.
        assertTrue(repo.lookupSnapshot(initial).metadata().containsKey("__icechunk"));
    }

    @Test
    void metadataMustBeJson() {
        CommitOptions.Builder builder = CommitOptions.builder().metadata("bad", new Object());
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitOptions.builder().metadata("nan", Double.NaN).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitOptions.builder()
                        .metadata("huge", BigInteger.ONE.shiftLeft(64))
                        .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitOptions.builder()
                        .metadata("decimal", new BigDecimal("0.1"))
                        .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitOptions.builder().metadata("path", Paths.get("a/b")).build());
        Object deep = Collections.emptyList();
        for (int i = 0; i < 101; i++) {
            deep = Collections.singletonList(deep);
        }
        Object tooDeep = deep;
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitOptions.builder().metadata("deep", tooDeep).build());
    }

    @Test
    void lookupSnapshot() {
        SnapshotId id = commit("zarr.json", GROUP, "root");
        SnapshotInfo info = repo.lookupSnapshot(id);
        assertEquals(id, info.id());
        assertEquals("root", info.message());
        assertEquals(initial, info.parentId().get());
        assertEquals(info, repo.ancestry(Version.snapshot(id)).get(0));
    }

    @Test
    void emptyCommitsNeedAllowEmpty() {
        try (Session session = repo.writableSession("main")) {
            assertThrows(IcechunkException.class, () -> session.commit("nothing"));
        }
        try (Session session = repo.writableSession("main")) {
            SnapshotId id = session.commit(
                    "nothing", CommitOptions.builder().allowEmpty(true).build());
            assertEquals(id, repo.lookupBranch("main"));
        }
    }

    @Test
    void resolveVersion() {
        SnapshotId id = commit("zarr.json", GROUP, "root");
        repo.createTag("t", id);
        assertEquals(id, repo.resolveVersion(Version.branch("main")));
        assertEquals(id, repo.resolveVersion(Version.tag("t")));
        assertEquals(initial, repo.resolveVersion(Version.snapshot(initial)));
    }

    @Test
    void asOfPicksTheSnapshotCurrentAtThatTime() throws InterruptedException {
        SnapshotId first = commit("zarr.json", GROUP, "first");
        Instant between = repo.lookupSnapshot(first).writtenAt().plusMillis(1);
        Thread.sleep(5);
        SnapshotId second = commit("a/zarr.json", GROUP, "second");

        assertEquals(first, repo.resolveVersion(Version.asOf("main", between)));
        Instant secondAt = repo.lookupSnapshot(second).writtenAt();
        assertEquals(second, repo.resolveVersion(Version.asOf("main", secondAt)));
        try (Session session = repo.readonlySession(Version.asOf("main", between))) {
            assertEquals(first, session.snapshotId());
            assertFalse(session.store().exists("a/zarr.json"));
        }
        assertEquals(2, repo.ancestry(Version.asOf("main", between)).size());

        Instant beforeRepository = repo.lookupSnapshot(initial).writtenAt().minusSeconds(60);
        assertThrows(IcechunkException.class, () -> repo.resolveVersion(Version.asOf("main", beforeRepository)));
    }

    @Test
    void diffBetweenVersions() {
        SnapshotId root = commit("zarr.json", GROUP, "root");
        SnapshotId array;
        try (Session session = repo.writableSession("main")) {
            session.store().set("data/zarr.json", ByteBuffer.wrap(ARRAY));
            session.store().set("data/c/0", ByteBuffer.wrap(CHUNK));
            array = session.commit("array");
        }

        Diff diff = repo.diff(Version.snapshot(root), Version.snapshot(array));
        assertEquals(Collections.singleton("/data"), diff.newArrays());
        assertEquals(Collections.emptySet(), diff.newGroups());
        assertEquals(
                Collections.singletonMap("/data", Collections.singletonList(Collections.singletonList(0L))),
                diff.updatedChunks());
        assertFalse(diff.isEmpty());

        assertThrows(IcechunkException.class, () -> repo.diff(Version.snapshot(array), Version.snapshot(root)));
        assertThrows(IcechunkException.class, () -> repo.diff(Version.snapshot(array), Version.branch("main")));
    }

    @Test
    void sessionStatusListsUncommittedChanges() {
        commit("zarr.json", GROUP, "root");
        try (Session session = repo.writableSession("main")) {
            assertTrue(session.status().isEmpty());
            session.store().set("g/zarr.json", ByteBuffer.wrap(GROUP));
            Diff status = session.status();
            assertEquals(Collections.singleton("/g"), status.newGroups());
            assertTrue(status.newArrays().isEmpty());
        }
    }

    @Test
    void resetBranchChecksTheExpectedTip() {
        SnapshotId first = commit("zarr.json", GROUP, "first");
        SnapshotId second = commit("a/zarr.json", GROUP, "second");

        assertThrows(ConflictException.class, () -> repo.resetBranch("main", initial, first));
        assertEquals(second, repo.lookupBranch("main"));
        repo.resetBranch("main", first, second);
        assertEquals(first, repo.lookupBranch("main"));
    }

    @Test
    void resetBranchConflictsOnSpecVersion1() {
        try (Storage v1Storage = Storage.inMemory();
                Repository v1 = Repository.create(
                        v1Storage, RepositoryOptions.builder().specVersion(1).build())) {
            SnapshotId root = v1.lookupBranch("main");
            SnapshotId tip;
            try (Session session = v1.writableSession("main")) {
                session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
                tip = session.commit("root");
            }
            assertThrows(ConflictException.class, () -> v1.resetBranch("main", root, root));
            assertEquals(tip, v1.lookupBranch("main"));
        }
    }

    @Test
    void versionsDescribeThemselves() {
        assertEquals("branch main", Version.branch("main").toString());
        assertEquals(Version.tag("v1"), Version.tag("v1"));
        assertFalse(Version.tag("v1").equals(Version.branch("v1")));
        assertEquals(
                "branch main as of 2026-01-02T03:04:05Z",
                Version.asOf("main", Instant.parse("2026-01-02T03:04:05Z")).toString());
        assertThrows(IllegalArgumentException.class, () -> Version.asOf("main", Instant.MAX));
    }

    @Test
    void loggingCanBeInitializedTwice() {
        Logging.initialize("error");
        Logging.initialize("off");
        assertThrows(IllegalArgumentException.class, () -> Logging.initialize("icechunk=verbose"));
    }
}
