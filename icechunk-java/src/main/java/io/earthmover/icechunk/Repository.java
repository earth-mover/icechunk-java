package io.earthmover.icechunk;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An icechunk repository: a versioned Zarr hierarchy with branches, tags and snapshots.
 *
 * <p>Reading and writing happen through a {@link Session}. A {@code Repository} is safe to share between threads, and
 * sessions it opened stay usable after it closes.
 *
 * <pre>{@code
 * try (Storage storage = Storage.localFilesystem(Paths.get("/tmp/my-repo"));
 *         Repository repo = Repository.create(storage);
 *         Session session = repo.writableSession("main")) {
 *     session.store().set("zarr.json", groupMetadata);
 *     SnapshotId id = session.commit("Create the root group");
 * }
 * }</pre>
 */
public final class Repository extends NativeHandle {
    Repository(long handle) {
        super(handle);
    }

    private static Repository open(Storage storage, int mode, RepositoryOptions options) {
        String json = Objects.requireNonNull(options, "options").toJson();
        try {
            return new Repository(Native.repositoryOpen(storage.handle(), mode, json));
        } finally {
            HandleCleaner.reachabilityFence(storage);
        }
    }

    /**
     * Open an existing repository.
     *
     * @throws IcechunkException if there is no repository at {@code storage}
     */
    public static Repository open(Storage storage) {
        return open(storage, RepositoryOptions.defaults());
    }

    public static Repository open(Storage storage, RepositoryOptions options) {
        return open(storage, Native.OPEN, options);
    }

    /**
     * Create a new, empty repository with a {@code main} branch.
     *
     * @throws IcechunkException if a repository already exists at {@code storage}
     */
    public static Repository create(Storage storage) {
        return create(storage, RepositoryOptions.defaults());
    }

    public static Repository create(Storage storage, RepositoryOptions options) {
        return open(storage, Native.CREATE, options);
    }

    /** Open the repository at {@code storage}, creating it first if there is none. */
    public static Repository openOrCreate(Storage storage) {
        return openOrCreate(storage, RepositoryOptions.defaults());
    }

    public static Repository openOrCreate(Storage storage, RepositoryOptions options) {
        return open(storage, Native.OPEN_OR_CREATE, options);
    }

    /** Returns true if {@code storage} holds a repository. */
    public static boolean exists(Storage storage) {
        try {
            return Native.repositoryExists(storage.handle());
        } finally {
            HandleCleaner.reachabilityFence(storage);
        }
    }

    /** The effective repository configuration, as a JSON document in icechunk's {@code RepositoryConfig} format. */
    public String configJson() {
        try {
            return Native.repositoryConfig(handle());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The names of all branches, sorted. */
    public Set<String> listBranches() {
        try {
            return sortedSet(Native.repositoryListBranches(handle()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The names of all tags, sorted. */
    public Set<String> listTags() {
        try {
            return sortedSet(Native.repositoryListTags(handle()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The snapshot at the tip of {@code branch}. */
    public SnapshotId lookupBranch(String branch) {
        try {
            return SnapshotId.of(Native.repositoryLookupBranch(handle(), branch));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The snapshot {@code tag} points to. */
    public SnapshotId lookupTag(String tag) {
        try {
            return SnapshotId.of(Native.repositoryLookupTag(handle(), tag));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Create {@code branch} pointing at {@code snapshot}. */
    public void createBranch(String branch, SnapshotId snapshot) {
        try {
            Native.repositoryCreateBranch(handle(), branch, snapshot.toString());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    public void deleteBranch(String branch) {
        try {
            Native.repositoryDeleteBranch(handle(), branch);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Move {@code branch} to {@code snapshot}, which may be any snapshot in the repository. */
    public void resetBranch(String branch, SnapshotId snapshot) {
        resetBranch(branch, snapshot, null);
    }

    /**
     * Move {@code branch} to {@code snapshot}, only if it still points to {@code expected}.
     *
     * @throws ConflictException if the branch has moved away from {@code expected}
     */
    public void resetBranch(String branch, SnapshotId snapshot, SnapshotId expected) {
        String from = expected == null ? null : expected.toString();
        try {
            Native.repositoryResetBranch(handle(), branch, snapshot.toString(), from);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Create {@code tag} pointing at {@code snapshot}. Tags cannot be moved. */
    public void createTag(String tag, SnapshotId snapshot) {
        try {
            Native.repositoryCreateTag(handle(), tag, snapshot.toString());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    public void deleteTag(String tag) {
        try {
            Native.repositoryDeleteTag(handle(), tag);
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** The history leading to {@code version}, newest first, ending with the repository's first snapshot. */
    public List<SnapshotInfo> ancestry(Version version) {
        String json;
        try {
            json = Native.repositoryAncestry(handle(), version.toJson());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        return SnapshotInfo.listFromJson(json);
    }

    /**
     * The details of one snapshot.
     *
     * @throws IcechunkException if there is no such snapshot
     */
    public SnapshotInfo lookupSnapshot(SnapshotId snapshot) {
        String json;
        try {
            json = Native.repositoryLookupSnapshot(handle(), snapshot.toString());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        return SnapshotInfo.fromJson(JsonReader.readObject(json));
    }

    /** The snapshot {@code version} refers to now. */
    public SnapshotId resolveVersion(Version version) {
        try {
            return SnapshotId.of(Native.repositoryResolveVersion(handle(), version.toJson()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /**
     * The changes made after {@code from}, up to and including {@code to}.
     *
     * @throws IcechunkException unless {@code from} is an earlier snapshot in the history of {@code to}; two versions
     *     that resolve to the same snapshot also throw
     */
    public Diff diff(Version from, Version to) {
        String json;
        try {
            json = Native.repositoryDiff(handle(), from.toJson(), to.toJson());
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
        return Diff.fromJson(json);
    }

    /** Open a session that reads {@code version} and cannot write. */
    public Session readonlySession(Version version) {
        try {
            return new Session(Native.repositoryReadonlySession(handle(), version.toJson()));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    /** Open a session that reads the tip of {@code branch} and can commit back to it. */
    public Session writableSession(String branch) {
        try {
            return new Session(Native.repositoryWritableSession(handle(), branch));
        } finally {
            HandleCleaner.reachabilityFence(this);
        }
    }

    private static Set<String> sortedSet(String[] names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
    }
}
