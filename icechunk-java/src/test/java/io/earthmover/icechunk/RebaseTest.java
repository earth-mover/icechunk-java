package io.earthmover.icechunk;

import static io.earthmover.icechunk.RepositoryTest.GROUP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rebasing sessions and resolving conflicts, amending, flushing, and moving nodes. */
class RebaseTest {
    /** Eight uint8 values in two chunks of four. */
    static final byte[] ARRAY = ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[8],\"data_type\":\"uint8\","
                    + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[4]}},"
                    + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                    + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}")
            .getBytes(UTF_8);

    private static final byte[] OURS = {1, 1, 1, 1};
    private static final byte[] THEIRS = {2, 2, 2, 2};

    private Storage storage;
    private Repository repo;

    @BeforeEach
    void open() {
        storage = Storage.inMemory();
        repo = Repository.create(storage);
        try (Session session = repo.writableSession("main")) {
            session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
            session.store().set("a/zarr.json", ByteBuffer.wrap(ARRAY));
            session.commit("array a");
        }
    }

    @AfterEach
    void close() {
        repo.close();
        storage.close();
    }

    private static byte[] withAttribute(String value) {
        return new String(ARRAY, UTF_8)
                .replace("\"attributes\":{}", "\"attributes\":{\"by\":\"" + value + "\"}")
                .getBytes(UTF_8);
    }

    private static void set(Session session, String key, byte[] value) {
        session.store().set(key, ByteBuffer.wrap(value));
    }

    private byte[] read(String key) {
        try (Session session = repo.readonlySession(Version.branch("main"))) {
            return session.store().get(key).orElse(null);
        }
    }

    /** Two sessions from the same tip; {@code theirs} commits first. */
    private void race(Session ours, String oursKey, String theirsKey) {
        set(ours, oursKey, OURS);
        try (Session theirs = repo.writableSession("main")) {
            set(theirs, theirsKey, THEIRS);
            theirs.commit("theirs");
        }
    }

    @Test
    void aSessionWithoutConflictsRebasesAndCommits() {
        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/1");
            assertThrows(ConflictException.class, () -> ours.commit("ours"));
            ours.rebase(ConflictSolver.detector());
            ours.commit("ours");
        }
        assertArrayEquals(OURS, read("a/c/0"));
        assertArrayEquals(THEIRS, read("a/c/1"));
        assertEquals(4, repo.ancestry(Version.branch("main")).size());
    }

    @Test
    void theDetectorReportsChunkConflicts() {
        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/0");
            SnapshotId theirs = repo.lookupBranch("main");
            RebaseFailedException e =
                    assertThrows(RebaseFailedException.class, () -> ours.rebase(ConflictSolver.detector()));
            assertEquals(theirs, e.snapshotId());
            assertEquals(1, e.conflicts().size());
            Conflict conflict = e.conflicts().get(0);
            assertEquals(Conflict.Type.CHUNK_DOUBLE_UPDATE, conflict.type());
            assertEquals("/a", conflict.path().get());
            assertEquals(Collections.singletonList(Collections.singletonList(0L)), conflict.chunks());
            assertTrue(e.getMessage().contains("CHUNK_DOUBLE_UPDATE /a [[0]]"), e.getMessage());
            // The session keeps its changes.
            assertTrue(ours.hasUncommittedChanges());
        }
    }

    @Test
    void theBasicSolverPicksASide() {
        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/0");
            ours.rebase(ConflictSolver.basic(VersionSelection.USE_THEIRS, false, false));
            ours.commit("ours");
        }
        assertArrayEquals(THEIRS, read("a/c/0"));

        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/0");
            ours.rebase(ConflictSolver.basic());
            ours.commit("ours");
        }
        assertArrayEquals(OURS, read("a/c/0"));

        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/0");
            RebaseFailedException e = assertThrows(
                    RebaseFailedException.class,
                    () -> ours.rebase(ConflictSolver.basic(VersionSelection.FAIL, false, false)));
            assertEquals(Conflict.Type.CHUNK_DOUBLE_UPDATE, e.conflicts().get(0).type());
        }
    }

    @Test
    void metadataConflictsAreNotSolvedByTheBasicSolver() {
        try (Session ours = repo.writableSession("main")) {
            set(ours, "a/zarr.json", withAttribute("ours"));
            try (Session theirs = repo.writableSession("main")) {
                set(theirs, "a/zarr.json", withAttribute("theirs"));
                theirs.commit("theirs");
            }
            RebaseFailedException e =
                    assertThrows(RebaseFailedException.class, () -> ours.rebase(ConflictSolver.basic()));
            assertEquals(
                    Conflict.Type.ZARR_METADATA_DOUBLE_UPDATE,
                    e.conflicts().get(0).type());
            assertEquals("/a", e.conflicts().get(0).path().get());
        }
    }

    @Test
    void aCommitCanRebaseByItself() {
        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/0");
            ours.commit(
                    "ours",
                    CommitOptions.builder()
                            .rebaseWith(ConflictSolver.basic(VersionSelection.USE_THEIRS, false, false))
                            .rebaseTries(3)
                            .build());
        }
        assertArrayEquals(THEIRS, read("a/c/0"));

        try (Session ours = repo.writableSession("main")) {
            race(ours, "a/c/0", "a/c/0");
            assertThrows(
                    RebaseFailedException.class,
                    () -> ours.commit(
                            "ours",
                            CommitOptions.builder()
                                    .rebaseWith(ConflictSolver.detector())
                                    .build()));
        }
        assertThrows(
                IllegalArgumentException.class, () -> CommitOptions.builder().rebaseTries(0));
    }

    @Test
    void amendReplacesTheTip() {
        SnapshotId tip = repo.lookupBranch("main");
        SnapshotId parent = repo.lookupSnapshot(tip).parentId().get();
        SnapshotId amended;
        try (Session session = repo.writableSession("main")) {
            set(session, "a/c/1", OURS);
            amended = session.amend(
                    "array a, with data",
                    CommitOptions.builder().metadata("k", "v").build());
        }
        List<SnapshotInfo> history = repo.ancestry(Version.branch("main"));
        assertEquals(2, history.size());
        assertEquals(amended, history.get(0).id());
        assertEquals(parent, history.get(0).parentId().get());
        assertEquals("array a, with data", history.get(0).message());
        assertEquals("v", history.get(0).metadata().get("k"));
        assertArrayEquals(OURS, read("a/c/1"));
    }

    @Test
    void flushWritesASnapshotWithoutMovingTheBranch() {
        SnapshotId tip = repo.lookupBranch("main");
        SnapshotId flushed;
        try (Session session = repo.writableSession("main")) {
            set(session, "a/c/0", OURS);
            flushed = session.flush("scratch");
            assertTrue(session.isReadOnly());
        }
        assertEquals(tip, repo.lookupBranch("main"));
        assertEquals(tip, repo.lookupSnapshot(flushed).parentId().get());
        try (Session session = repo.readonlySession(Version.snapshot(flushed))) {
            assertArrayEquals(OURS, session.store().get("a/c/0").get());
        }
        repo.createBranch("scratch", flushed);
        assertEquals(flushed, repo.lookupBranch("scratch"));
    }

    @Test
    void nodesMoveInARearrangeSession() {
        try (Session session = repo.writableSession("main")) {
            set(session, "a/c/0", OURS);
            set(session, "g/zarr.json", GROUP);
            session.commit("data and a group");
        }
        SnapshotId before = repo.lookupBranch("main");
        try (Session session = repo.rearrangeSession("main")) {
            session.move("/a", "/g/b");
            assertEquals(
                    Collections.singletonList("/a -> /g/b"),
                    Arrays.asList(session.status().movedNodes().get(0).toString()));
            session.commit("move a");
        }
        assertArrayEquals(OURS, read("g/b/c/0"));
        assertEquals(null, read("a/zarr.json"));
        Diff diff = repo.diff(Version.snapshot(before), Version.branch("main"));
        assertEquals("/a", diff.movedNodes().get(0).from());
        assertEquals("/g/b", diff.movedNodes().get(0).to());

        try (Session session = repo.rearrangeSession("main")) {
            assertThrows(IcechunkException.class, () -> session.move("/missing", "/x"));
            assertThrows(IcechunkException.class, () -> session.move("/g", "/g/inside"));
            assertThrows(IllegalArgumentException.class, () -> session.move("relative", "/x"));
            assertThrows(IcechunkException.class, () -> set(session, "g/b/c/1", OURS));
        }
        try (Session session = repo.writableSession("main")) {
            assertThrows(IcechunkException.class, () -> session.move("/g/b", "/b"));
        }
        assertFalse(repo.listBranches().isEmpty());
    }
}
