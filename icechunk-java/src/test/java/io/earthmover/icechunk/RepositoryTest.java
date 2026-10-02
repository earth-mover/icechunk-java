package io.earthmover.icechunk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Creating and opening repositories, branches and tags, sessions, and commit conflicts. */
class RepositoryTest {
    static final byte[] GROUP = "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}".getBytes(UTF_8);

    @Test
    void createWriteCommitAndReadBack(@TempDir Path dir) {
        SnapshotId first;
        try (Storage storage = Storage.localFilesystem(dir);
                Repository repo = Repository.create(storage)) {
            assertEquals(setOf("main"), repo.listBranches());
            try (Session session = repo.writableSession("main")) {
                assertFalse(session.isReadOnly());
                assertEquals(Optional.of("main"), session.branch());
                session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
                assertTrue(session.hasUncommittedChanges());
                first = session.commit("root group");
                assertFalse(session.hasUncommittedChanges());
                assertEquals(first, session.snapshotId());
            }
            assertEquals(first, repo.lookupBranch("main"));
        }

        try (Storage storage = Storage.localFilesystem(dir);
                Repository repo = Repository.open(storage);
                Session session = repo.readonlySession(Version.branch("main"))) {
            assertTrue(session.isReadOnly());
            assertArrayEquals(GROUP, session.store().get("zarr.json").get());
        }
    }

    @Test
    void branchesTagsAndTimeTravel() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage)) {
            SnapshotId v1;
            SnapshotId v2;
            try (Session session = repo.writableSession("main")) {
                session.store().set("zarr.json", ByteBuffer.wrap(GROUP));
                v1 = session.commit("v1");
            }
            repo.createTag("v1", v1);
            repo.createBranch("dev", v1);
            try (Session session = repo.writableSession("main")) {
                session.store().set("a/zarr.json", ByteBuffer.wrap(GROUP));
                v2 = session.commit("v2");
            }

            List<SnapshotInfo> history = repo.ancestry(Version.branch("main"));
            assertEquals(
                    Arrays.asList("v2", "v1", "Repository initialized"),
                    history.stream().map(SnapshotInfo::message).collect(Collectors.toList()));
            assertEquals(v2, history.get(0).id());
            assertEquals(Optional.of(v1), history.get(0).parentId());
            assertEquals(Optional.empty(), history.get(2).parentId());
            assertTrue(history.get(0)
                    .writtenAt()
                    .isAfter(history.get(1).writtenAt().minusSeconds(1)));
            assertEquals(2, repo.ancestry(Version.tag("v1")).size());

            assertEquals(setOf("dev", "main"), repo.listBranches());
            assertEquals(setOf("v1"), repo.listTags());
            assertEquals(v1, repo.lookupTag("v1"));

            try (Session old = repo.readonlySession(Version.tag("v1"))) {
                assertFalse(old.store().exists("a/zarr.json"));
                assertEquals(Optional.empty(), old.branch());
            }
            try (Session old = repo.readonlySession(Version.snapshot(v1))) {
                assertFalse(old.store().exists("a/zarr.json"));
            }
            try (Session tip = repo.readonlySession(Version.branch("main"))) {
                assertTrue(tip.store().exists("a/zarr.json"));
            }

            repo.resetBranch("dev", v2);
            assertEquals(v2, repo.lookupBranch("dev"));
            repo.deleteBranch("dev");
            repo.deleteTag("v1");
            assertEquals(setOf("main"), repo.listBranches());
            assertEquals(Collections.emptySet(), repo.listTags());
        }
    }

    @Test
    void concurrentCommitsConflict() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage);
                Session first = repo.writableSession("main");
                Session second = repo.writableSession("main")) {
            first.store().set("zarr.json", ByteBuffer.wrap(GROUP));
            second.store().set("zarr.json", ByteBuffer.wrap(GROUP));
            first.commit("first");
            assertThrows(ConflictException.class, () -> second.commit("second"));
        }
    }

    @Test
    void openingAMissingRepositoryFails(@TempDir Path dir) {
        try (Storage storage = Storage.localFilesystem(dir.resolve("missing"))) {
            assertFalse(Repository.exists(storage));
            assertThrows(IcechunkException.class, () -> Repository.open(storage));
        }
    }

    @Test
    void openOrCreateCreatesOnce(@TempDir Path dir) {
        try (Storage storage = Storage.localFilesystem(dir)) {
            Repository.openOrCreate(storage).close();
            assertTrue(Repository.exists(storage));
            try (Repository repo = Repository.openOrCreate(storage)) {
                assertEquals(setOf("main"), repo.listBranches());
            }
        }
    }

    @Test
    void readOnlySessionsRejectWrites() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage);
                Session session = repo.readonlySession(Version.branch("main"))) {
            assertThrows(IcechunkException.class, () -> session.store().set("zarr.json", ByteBuffer.wrap(GROUP)));
        }
    }

    @Test
    void badSnapshotIdIsAnIllegalArgument() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(storage)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repo.readonlySession(Version.snapshot(SnapshotId.of("not-an-id"))));
        }
    }

    @Test
    void configIsJson() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(
                        storage,
                        RepositoryOptions.builder()
                                .configJson("{\"inline_chunk_threshold_bytes\":12}")
                                .build())) {
            assertTrue(repo.configJson().contains("\"inline_chunk_threshold_bytes\":12"), repo.configJson());
        }
    }

    private static Set<String> setOf(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }
}
