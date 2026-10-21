package io.earthmover.icechunk;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.Invariant;
import dev.hegel.Rule;
import dev.hegel.Stateful;
import dev.hegel.TestCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Random sequences of commits, branch and tag operations, snapshot expiration and garbage collection, checked against
 * a model of the repository's history. A port of the version control state machine in icechunk-python's
 * {@code tests/test_stateful_repo_ops.py}, for spec version 2. The Java API has no amend, repository metadata or
 * operations log, so the rules and checks for those are left out.
 *
 * <p>The repository lives on the local filesystem, where an object's creation time, which garbage collection
 * compares with its cutoff, is the file's modification time.
 */
class VersionControlTest {
    private static final String MAIN = "main";
    private static final List<String> BRANCHES = List.of(MAIN, "dev", "feature", "x y");
    private static final List<String> TAGS = List.of("v1", "v2", "release", "x y");
    private static final List<String> PATHS = List.of("zarr.json", "a/zarr.json", "b/zarr.json");
    private static final Instant EPOCH = Instant.parse("2000-01-01T00:00:00Z");

    @HegelTest
    void historyMatchesModel(TestCase tc) {
        try (VersionControlMachine machine = new VersionControlMachine()) {
            Stateful.run(machine, tc);
        }
    }

    /** A snapshot as the model knows it. */
    private static final class Commit {
        final SnapshotId id;
        final Instant writtenAt;
        final SortedMap<String, byte[]> store;
        SnapshotId parent;

        Commit(SnapshotInfo info, SortedMap<String, byte[]> store) {
            this.id = info.id();
            this.writtenAt = info.writtenAt();
            this.parent = info.parentId().orElse(null);
            this.store = new TreeMap<>(store);
        }
    }

    static final class VersionControlMachine implements AutoCloseable {
        private final Path dir;
        private final Storage storage;
        private Repository repo;
        private Session session;

        private final SnapshotId initial;
        /** Snapshots in the repository's history: not expired, not collected. */
        private final Map<SnapshotId, Commit> commits = new LinkedHashMap<>();
        /** Snapshots whose files are still in storage, expired or not. */
        private final Map<SnapshotId, Commit> onDisk = new LinkedHashMap<>();
        /** Every snapshot ever made, for rules that should also try ones that are gone. */
        private final List<SnapshotId> everMade = new ArrayList<>();

        private final Map<String, SnapshotId> branches = new TreeMap<>();
        private final Map<String, SnapshotId> tags = new TreeMap<>();
        /** A tag name, once used, can never be used again, even after the tag is deleted. */
        private final Set<String> usedTagNames = new HashSet<>();

        /** The checked-out branch, or null for a read-only session on a snapshot. */
        private String branch;
        /** The keys and values the session should hold. */
        private SortedMap<String, byte[]> store = new TreeMap<>();

        private boolean changed;
        private int version;

        VersionControlMachine() {
            try {
                dir = Files.createTempDirectory("icechunk-history");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            storage = Storage.localFilesystem(dir);
            repo = Repository.create(storage);
            SnapshotInfo root = repo.ancestry(Version.branch(MAIN)).get(0);
            initial = root.id();
            record(root);
            branches.put(MAIN, initial);
            session = repo.writableSession(MAIN);
            branch = MAIN;
        }

        @Rule(weight = 3)
        void setDoc(TestCase tc) {
            String path = tc.draw(sampledFrom(PATHS), "path");
            byte[] doc = array(version++);
            if (branch == null) {
                assertThrows(IcechunkException.class, () -> session.store().set(path, ByteBuffer.wrap(doc)));
                return;
            }
            session.store().set(path, ByteBuffer.wrap(doc));
            store.put(path, doc);
            changed = true;
        }

        @Rule(weight = 2)
        void setAndCommit(TestCase tc) {
            int n = tc.draw(integers().min(1).max(5), "commits");
            for (int i = 0; i < n; i++) {
                setDoc(tc);
                commit(tc);
            }
        }

        @Rule(weight = 2)
        void commit(TestCase tc) {
            if (!changed) {
                return;
            }
            SnapshotId id = session.commit("commit " + version);
            SnapshotInfo info = repo.ancestry(Version.branch(branch)).get(0);
            assertEquals(id, info.id());
            assertEquals(branches.get(branch), info.parentId().orElse(null));
            record(info);
            branches.put(branch, id);
            changed = false;
            session.close();
            session = repo.writableSession(branch);
        }

        @Rule
        void discardChanges(TestCase tc) {
            if (branch == null) {
                assertThrows(IcechunkException.class, () -> session.discardChanges());
                return;
            }
            session.discardChanges();
            assertFalse(session.hasUncommittedChanges());
            store = new TreeMap<>(commits.get(branches.get(branch)).store);
            changed = false;
        }

        @Rule
        void checkoutCommit(TestCase tc) {
            SnapshotId id = tc.draw(sampledFrom(everMade), "snapshot");
            if (!commits.containsKey(id)) {
                assertThrows(
                        IcechunkException.class,
                        () -> repo.readonlySession(Version.snapshot(id)).close());
                return;
            }
            Session readonly = repo.readonlySession(Version.snapshot(id));
            assertTrue(readonly.isReadOnly());
            session.close();
            session = readonly;
            branch = null;
            store = new TreeMap<>(commits.get(id).store);
            changed = false;
        }

        @Rule
        void checkoutTag(TestCase tc) {
            String tag = tc.draw(sampledFrom(TAGS), "tag");
            SnapshotId id = tags.get(tag);
            if (id == null || !commits.containsKey(id)) {
                assertThrows(
                        IcechunkException.class,
                        () -> repo.readonlySession(Version.tag(tag)).close());
                return;
            }
            Session readonly = repo.readonlySession(Version.tag(tag));
            assertEquals(id, readonly.snapshotId());
            session.close();
            session = readonly;
            branch = null;
            store = new TreeMap<>(commits.get(id).store);
            changed = false;
        }

        @Rule
        void checkoutBranchRule(TestCase tc) {
            String name = tc.draw(sampledFrom(BRANCHES), "branch");
            if (!branches.containsKey(name)) {
                assertThrows(
                        IcechunkException.class,
                        () -> repo.writableSession(name).close());
                return;
            }
            checkoutBranch(name);
        }

        @Rule
        void createBranch(TestCase tc) {
            String name = tc.draw(sampledFrom(BRANCHES), "branch");
            SnapshotId id = tc.draw(sampledFrom(everMade), "snapshot");
            if (branches.containsKey(name) || !commits.containsKey(id)) {
                assertThrows(RuntimeException.class, () -> repo.createBranch(name, id));
                return;
            }
            repo.createBranch(name, id);
            branches.put(name, id);
        }

        @Rule
        void createTag(TestCase tc) {
            String name = tc.draw(sampledFrom(TAGS), "tag");
            SnapshotId id = tc.draw(sampledFrom(everMade), "snapshot");
            if (usedTagNames.contains(name) || !commits.containsKey(id)) {
                assertThrows(RuntimeException.class, () -> repo.createTag(name, id));
                return;
            }
            repo.createTag(name, id);
            tags.put(name, id);
            usedTagNames.add(name);
        }

        @Rule
        void resetBranch(TestCase tc) {
            String name = tc.draw(sampledFrom(BRANCHES), "branch");
            SnapshotId id = tc.draw(sampledFrom(everMade), "snapshot");
            if (!branches.containsKey(name) || !commits.containsKey(id)) {
                assertThrows(RuntimeException.class, () -> repo.resetBranch(name, id));
                return;
            }
            repo.resetBranch(name, id);
            branches.put(name, id);
            // A session on the branch would conflict at its next commit, so start a new one.
            if (name.equals(branch)) {
                checkoutBranch(name);
            }
        }

        @Rule
        void deleteBranch(TestCase tc) {
            String name = tc.draw(sampledFrom(BRANCHES), "branch");
            if (name.equals(MAIN) || !branches.containsKey(name)) {
                assertThrows(RuntimeException.class, () -> repo.deleteBranch(name));
                return;
            }
            repo.deleteBranch(name);
            branches.remove(name);
            if (name.equals(branch)) {
                checkoutBranch(MAIN);
            }
        }

        @Rule
        void deleteTag(TestCase tc) {
            String name = tc.draw(sampledFrom(TAGS), "tag");
            if (!tags.containsKey(name)) {
                assertThrows(RuntimeException.class, () -> repo.deleteTag(name));
                return;
            }
            repo.deleteTag(name);
            tags.remove(name);
        }

        @Rule
        void reopenRepository(TestCase tc) {
            repo.close();
            repo = Repository.open(storage);
            checkoutBranch(branch != null && branches.containsKey(branch) ? branch : MAIN);
        }

        @Rule
        void expireSnapshots(TestCase tc) {
            Instant olderThan = drawOlderThan(tc);
            boolean deleteBranches = tc.draw(booleans(), "deleteExpiredBranches");
            boolean deleteTags = tc.draw(booleans(), "deleteExpiredTags");
            ExpireResult result = repo.expireSnapshots(
                    olderThan,
                    ExpireOptions.builder()
                            .deleteExpiredBranches(deleteBranches)
                            .deleteExpiredTags(deleteTags)
                            .build());
            Set<SnapshotId> released = result.releasedSnapshots();
            tc.note("released " + released.size() + " snapshots");

            assertFalse(released.contains(branches.get(MAIN)), "main's tip was released");
            assertFalse(released.contains(initial), "the initial snapshot was released");
            Set<SnapshotId> branchTips = new HashSet<>(branches.values());
            Set<SnapshotId> tagTips = new HashSet<>(tags.values());
            for (SnapshotId id : released) {
                Commit commit = commits.get(id);
                assertTrue(commit != null, id + " was released but is not in the history");
                assertTrue(commit.writtenAt.isBefore(olderThan), id + " was released but is not old enough");
                assertTrue(commit.parent != null, id + " was released but has no parent");
                if (!deleteTags) {
                    assertFalse(tagTips.contains(id), id + " was released but a kept tag points to it");
                }
                if (!deleteBranches) {
                    assertFalse(branchTips.contains(id), id + " was released but a kept branch points to it");
                }
            }

            // Refs are deleted when they point to any snapshot old enough to expire, whether or not it was released.
            Set<SnapshotId> oldEnough = commits.values().stream()
                    .filter(c -> c.writtenAt.isBefore(olderThan) && c.parent != null)
                    .map(c -> c.id)
                    .collect(Collectors.toSet());
            Set<String> expectedBranches = new HashSet<>();
            if (deleteBranches) {
                branches.forEach((name, id) -> {
                    if (!name.equals(MAIN) && oldEnough.contains(id)) {
                        expectedBranches.add(name);
                    }
                });
            }
            Set<String> expectedTags = new HashSet<>();
            if (deleteTags) {
                tags.forEach((name, id) -> {
                    if (oldEnough.contains(id)) {
                        expectedTags.add(name);
                    }
                });
            }
            assertEquals(expectedBranches, result.deletedBranches(), "deleted branches");
            assertEquals(expectedTags, result.deletedTags(), "deleted tags");

            for (SnapshotId id : released) {
                commits.remove(id);
            }
            for (Commit commit : commits.values()) {
                if (released.contains(commit.parent)) {
                    commit.parent = initial;
                }
                assertFalse(
                        released.contains(
                                repo.lookupSnapshot(commit.id).parentId().orElse(null)),
                        commit.id + " kept a released parent");
            }
            expectedBranches.forEach(branches::remove);
            expectedTags.forEach(tags::remove);
            if (branch != null && expectedBranches.contains(branch)) {
                checkoutBranch(MAIN);
            }
        }

        @Rule
        void garbageCollect(TestCase tc) {
            Instant olderThan = drawOlderThan(tc);
            Map<SnapshotId, Instant> createdAt = new HashMap<>();
            for (SnapshotId id : onDisk.keySet()) {
                createdAt.put(id, modified("snapshots/" + id));
            }
            Set<SnapshotId> reachable = reachable();
            Set<SnapshotId> expected = new HashSet<>();
            for (SnapshotId id : onDisk.keySet()) {
                if (!reachable.contains(id) && createdEntirelyBefore(createdAt.get(id), olderThan)) {
                    expected.add(id);
                }
            }
            GcSummary summary = repo.garbageCollect(olderThan);
            tc.note(summary.toString());
            assertEquals(expected.size(), summary.snapshotsDeleted(), "snapshots deleted, expected " + expected);
            assertEquals(0, summary.objectsFailedToDelete(), "failed deletes");

            // A kept snapshot whose parents were collected is re-parented to its nearest surviving ancestor.
            for (Commit commit : commits.values()) {
                if (expected.contains(commit.id)) {
                    continue;
                }
                SnapshotId parent = commit.parent;
                while (parent != null && expected.contains(parent)) {
                    Commit ancestor = onDisk.get(parent);
                    parent = ancestor == null ? null : ancestor.parent;
                }
                commit.parent = parent == null && commit.parent != null ? initial : parent;
            }
            for (SnapshotId id : expected) {
                commits.remove(id);
                onDisk.remove(id);
            }
            if (branch == null && expected.contains(session.snapshotId())) {
                checkoutBranch(MAIN);
            }
        }

        @Invariant(alwaysRun = true)
        void refsMatchModel(TestCase tc) {
            Map<String, SnapshotId> actualBranches = new TreeMap<>();
            for (String name : repo.listBranches()) {
                actualBranches.put(name, repo.lookupBranch(name));
            }
            assertEquals(branches, actualBranches, "branches");
            Map<String, SnapshotId> actualTags = new TreeMap<>();
            for (String name : repo.listTags()) {
                actualTags.put(name, repo.lookupTag(name));
            }
            assertEquals(tags, actualTags, "tags");
        }

        @Invariant
        void ancestryIsConsistent(TestCase tc) {
            for (String name : branches.keySet()) {
                checkAncestry(repo.ancestry(Version.branch(name)), "branch " + name);
            }
            for (String name : tags.keySet()) {
                checkAncestry(repo.ancestry(Version.tag(name)), "tag " + name);
            }
            for (Commit commit : commits.values()) {
                assertEquals(
                        commit.writtenAt, repo.lookupSnapshot(commit.id).writtenAt(), "written at of " + commit.id);
            }
        }

        @Invariant(alwaysRun = true)
        void sessionMatchesModel(TestCase tc) {
            Store actual = session.store();
            List<String> keys = new ArrayList<>(actual.list());
            keys.sort(null);
            assertEquals(new ArrayList<>(store.keySet()), keys, "keys of the session");
            for (Map.Entry<String, byte[]> entry : store.entrySet()) {
                assertEquals(
                        JsonReader.parse(new String(entry.getValue(), UTF_8)),
                        JsonReader.parse(new String(actual.get(entry.getKey()).orElseThrow(), UTF_8)),
                        entry.getKey());
            }
            if (changed) {
                assertTrue(session.hasUncommittedChanges(), "a changed session reports no changes");
            }
        }

        private void checkAncestry(List<SnapshotInfo> ancestry, String what) {
            Set<SnapshotId> seen = new HashSet<>();
            for (int i = 0; i < ancestry.size(); i++) {
                SnapshotInfo snap = ancestry.get(i);
                assertTrue(commits.containsKey(snap.id()), what + ": " + snap.id() + " is not in the model's history");
                assertTrue(seen.add(snap.id()), what + ": " + snap.id() + " appears twice");
                boolean last = i == ancestry.size() - 1;
                assertEquals(last, snap.parentId().isEmpty(), what + ": only the last snapshot has no parent");
                if (!last) {
                    assertTrue(
                            snap.writtenAt().isAfter(ancestry.get(i + 1).writtenAt()),
                            what + ": written times do not decrease");
                    assertNotEquals(snap.id(), ancestry.get(i + 1).id());
                }
            }
        }

        private void checkoutBranch(String name) {
            session.close();
            session = repo.writableSession(name);
            branch = name;
            store = new TreeMap<>(commits.get(branches.get(name)).store);
            changed = false;
        }

        private void record(SnapshotInfo info) {
            Commit commit = new Commit(info, store);
            commits.put(commit.id, commit);
            onDisk.put(commit.id, commit);
            everMade.add(commit.id);
        }

        /** Snapshots a branch or tag leads to, and the initial snapshot. */
        private Set<SnapshotId> reachable() {
            Set<SnapshotId> reachable = new HashSet<>();
            reachable.add(initial);
            List<SnapshotId> tips = new ArrayList<>(branches.values());
            tips.addAll(tags.values());
            for (SnapshotId tip : tips) {
                SnapshotId id = tip;
                while (id != null && commits.containsKey(id)) {
                    reachable.add(id);
                    id = commits.get(id).parent;
                }
            }
            return reachable;
        }

        /**
         * A cutoff for expiration or garbage collection: past every snapshot, just after one of them, or before all
         * of them. A snapshot and its transaction log are written separately, and expiration compares the snapshot's
         * written time rather than its file's, so each snapshot's time is the latest of the three.
         */
        private Instant drawOlderThan(TestCase tc) {
            List<Instant> times = new ArrayList<>();
            for (SnapshotId id : onDisk.keySet()) {
                Instant time = modified("snapshots/" + id);
                Path tx = dir.resolve("transactions").resolve(id.toString());
                if (Files.exists(tx)) {
                    time = latest(time, modified("transactions/" + id));
                }
                Commit commit = commits.get(id);
                times.add(latest(time, commit == null ? EPOCH : commit.writtenAt));
            }
            times.sort(Comparator.reverseOrder());
            return switch (tc.draw(integers().min(0).max(2), "cutoffKind")) {
                case 0 -> times.get(0).plus(Duration.ofDays(1));
                case 1 ->
                    times.get(tc.draw(integers().min(0).max(255), "cutoff") % times.size())
                            .plusNanos(1000);
                default -> EPOCH;
            };
        }

        private Instant modified(String key) {
            try {
                return Files.getLastModifiedTime(dir.resolve(key)).toInstant();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            session.close();
            repo.close();
            storage.close();
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Whether an object created at {@code createdAt} counts as created before {@code cutoff}. A store may floor a
     * creation time to a whole second, so icechunk counts such a time as the whole second.
     */
    @SuppressWarnings("JavaInstantGetSecondsGetNano") // The fraction of the second is the point.
    private static boolean createdEntirelyBefore(Instant createdAt, Instant cutoff) {
        if (createdAt.getNano() == 0) {
            return !createdAt.plusSeconds(1).isAfter(cutoff);
        }
        return createdAt.isBefore(cutoff);
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static byte[] array(int version) {
        return ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[4],\"data_type\":\"uint8\","
                        + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2]}},"
                        + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                        + "\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{\"version\":" + version
                        + "}}")
                .getBytes(UTF_8);
    }
}
