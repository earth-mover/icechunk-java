package io.earthmover.icechunk;

import java.time.Instant;
import java.util.ArrayList;
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
        long storageHandle = storage.handle();
        String json = Objects.requireNonNull(options, "options").toJson();
        return new Repository(NativeCall.runLong(call -> Native.repositoryOpen(call, storageHandle, mode, json)));
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
        long storageHandle = storage.handle();
        return NativeCall.runBoolean(call -> Native.repositoryExists(call, storageHandle));
    }

    /** The effective repository configuration, as a JSON document in icechunk's {@code RepositoryConfig} format. */
    public String configJson() {
        long h = handle();
        return NativeCall.runString(call -> Native.repositoryConfig(call, h));
    }

    /** The names of all branches, sorted. */
    public Set<String> listBranches() {
        long h = handle();
        return sortedSet(NativeCall.runStrings(call -> Native.repositoryListBranches(call, h)));
    }

    /** The names of all tags, sorted. */
    public Set<String> listTags() {
        long h = handle();
        return sortedSet(NativeCall.runStrings(call -> Native.repositoryListTags(call, h)));
    }

    /** The snapshot at the tip of {@code branch}. */
    public SnapshotId lookupBranch(String branch) {
        long h = handle();
        return SnapshotId.of(NativeCall.runString(call -> Native.repositoryLookupBranch(call, h, branch)));
    }

    /** The snapshot {@code tag} points to. */
    public SnapshotId lookupTag(String tag) {
        long h = handle();
        return SnapshotId.of(NativeCall.runString(call -> Native.repositoryLookupTag(call, h, tag)));
    }

    /** Create {@code branch} pointing at {@code snapshot}. */
    public void createBranch(String branch, SnapshotId snapshot) {
        long h = handle();
        String id = snapshot.toString();
        NativeCall.runVoid(call -> Native.repositoryCreateBranch(call, h, branch, id));
    }

    public void deleteBranch(String branch) {
        long h = handle();
        NativeCall.runVoid(call -> Native.repositoryDeleteBranch(call, h, branch));
    }

    /** Move {@code branch} to {@code snapshot}, which may be any snapshot in the repository. */
    public void resetBranch(String branch, SnapshotId snapshot) {
        long h = handle();
        String id = snapshot.toString();
        NativeCall.runVoid(call -> Native.repositoryResetBranch(call, h, branch, id));
    }

    /** Create {@code tag} pointing at {@code snapshot}. Tags cannot be moved. */
    public void createTag(String tag, SnapshotId snapshot) {
        long h = handle();
        String id = snapshot.toString();
        NativeCall.runVoid(call -> Native.repositoryCreateTag(call, h, tag, id));
    }

    public void deleteTag(String tag) {
        long h = handle();
        NativeCall.runVoid(call -> Native.repositoryDeleteTag(call, h, tag));
    }

    /** The history leading to {@code version}, newest first, ending with the repository's first snapshot. */
    public List<SnapshotInfo> ancestry(Version version) {
        long h = handle();
        int kind = version.kind();
        String value = version.value();
        List<String> fields = NativeCall.runStrings(call -> Native.repositoryAncestry(call, h, kind, value));
        List<SnapshotInfo> history = new ArrayList<>(fields.size() / 4);
        for (int i = 0; i + 3 < fields.size(); i += 4) {
            String parent = fields.get(i + 1);
            history.add(new SnapshotInfo(
                    SnapshotId.of(fields.get(i)),
                    parent.isEmpty() ? null : SnapshotId.of(parent),
                    Instant.parse(fields.get(i + 2)),
                    fields.get(i + 3)));
        }
        return Collections.unmodifiableList(history);
    }

    /** Open a session that reads {@code version} and cannot write. */
    public Session readonlySession(Version version) {
        long h = handle();
        int kind = version.kind();
        String value = version.value();
        return new Session(NativeCall.runLong(call -> Native.repositoryReadonlySession(call, h, kind, value)));
    }

    /** Open a session that reads the tip of {@code branch} and can commit back to it. */
    public Session writableSession(String branch) {
        long h = handle();
        return new Session(NativeCall.runLong(call -> Native.repositoryWritableSession(call, h, branch)));
    }

    private static Set<String> sortedSet(Iterable<String> names) {
        Set<String> set = new LinkedHashSet<>();
        names.forEach(set::add);
        return Collections.unmodifiableSet(set);
    }
}
