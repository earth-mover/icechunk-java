package io.earthmover.icechunk;

import java.lang.ref.Reference;
import java.time.Instant;
import java.util.ArrayList;
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
            Reference.reachabilityFence(storage);
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
            Reference.reachabilityFence(storage);
        }
    }

    /** The effective repository configuration, as a JSON document in icechunk's {@code RepositoryConfig} format. */
    public String configJson() {
        try {
            return Native.repositoryConfig(handle());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The names of all branches, sorted. */
    public Set<String> listBranches() {
        try {
            return sortedSet(Native.repositoryListBranches(handle()));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The names of all tags, sorted. */
    public Set<String> listTags() {
        try {
            return sortedSet(Native.repositoryListTags(handle()));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The snapshot at the tip of {@code branch}. */
    public SnapshotId lookupBranch(String branch) {
        try {
            return SnapshotId.of(Native.repositoryLookupBranch(handle(), branch));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The snapshot {@code tag} points to. */
    public SnapshotId lookupTag(String tag) {
        try {
            return SnapshotId.of(Native.repositoryLookupTag(handle(), tag));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Create {@code branch} pointing at {@code snapshot}. */
    public void createBranch(String branch, SnapshotId snapshot) {
        try {
            Native.repositoryCreateBranch(handle(), branch, snapshot.toString());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public void deleteBranch(String branch) {
        try {
            Native.repositoryDeleteBranch(handle(), branch);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Move {@code branch} to {@code snapshot}, which may be any snapshot in the repository. */
    public void resetBranch(String branch, SnapshotId snapshot) {
        try {
            Native.repositoryResetBranch(handle(), branch, snapshot.toString());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Create {@code tag} pointing at {@code snapshot}. Tags cannot be moved. */
    public void createTag(String tag, SnapshotId snapshot) {
        try {
            Native.repositoryCreateTag(handle(), tag, snapshot.toString());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public void deleteTag(String tag) {
        try {
            Native.repositoryDeleteTag(handle(), tag);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The history leading to {@code version}, newest first, ending with the repository's first snapshot. */
    public List<SnapshotInfo> ancestry(Version version) {
        String[] fields;
        try {
            fields = Native.repositoryAncestry(handle(), version.kind(), version.value());
        } finally {
            Reference.reachabilityFence(this);
        }
        List<SnapshotInfo> history = new ArrayList<>(fields.length / 4);
        for (int i = 0; i + 3 < fields.length; i += 4) {
            String parent = fields[i + 1];
            history.add(new SnapshotInfo(
                    SnapshotId.of(fields[i]),
                    parent.isEmpty() ? null : SnapshotId.of(parent),
                    Instant.parse(fields[i + 2]),
                    fields[i + 3]));
        }
        return Collections.unmodifiableList(history);
    }

    /** Open a session that reads {@code version} and cannot write. */
    public Session readonlySession(Version version) {
        try {
            return new Session(Native.repositoryReadonlySession(handle(), version.kind(), version.value()));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Open a session that reads the tip of {@code branch} and can commit back to it. */
    public Session writableSession(String branch) {
        try {
            return new Session(Native.repositoryWritableSession(handle(), branch));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private static Set<String> sortedSet(String[] names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
    }
}
