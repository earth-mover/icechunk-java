# Java API

`icechunk-java` opens a repository in a `Storage`, reads or writes one version of it through a `Session`, and moves
Zarr keys and bytes through that session's `Store`.

- The concepts are icechunk-python's, under Java names: `readonly_session(tag="v1")` in Python is
  `readonlySession(Version.tag("v1"))` here. The [icechunk docs](https://icechunk.io) explain repositories, sessions
  and version control in depth.
- A `Store` handles raw keys only. To work with arrays, wrap a session for zarr-java or N5, as in
  [Read a version](#read-a-version).
- `Storage`, `Repository`, `Session` and `Store` hold native resources, so close them
  ([Closing and threads](#closing-and-threads)).

## Open a repository

Pick a storage backend, then open or create the repository in it:

```java
Storage local = Storage.localFilesystem(Paths.get("/data/my-repo"));
Storage memory = Storage.inMemory();
Storage s3 = Storage.s3(S3Options.builder("my-bucket")
        .prefix("my-repo")
        .region("us-east-1")
        .build());                       // credentials from the standard AWS chain
Storage publicS3 = Storage.s3(S3Options.builder("icechunk-public-data")
        .prefix("v1/era5_weatherbench2")
        .region("us-east-1")
        .credentials(S3Credentials.anonymous())
        .build());
Storage gcs = Storage.gcs(GcsOptions.builder("my-bucket").prefix("my-repo").build());
Storage azure = Storage.azure(AzureOptions.builder("account", "container").prefix("my-repo").build());

Repository repo = Repository.open(s3);   // or create(...), openOrCreate(...)
```

`Repository.open` takes an optional `RepositoryOptions`, which carries credentials for virtual chunk containers and
configuration overrides.

## Read a version

A session reads one version: the tip of a branch, a tag, a snapshot, or a branch as it was at a point in time.

```java
try (Session session = repo.readonlySession(Version.branch("main"))) {
    Store store = session.store();
    List<String> children = store.listDir("");
    Optional<byte[]> metadata = store.get("temperature/zarr.json");
    Optional<byte[]> lastBytes = store.get("temperature/c/0/0", ByteRange.suffix(16));
}

repo.readonlySession(Version.tag("v1.0"));
repo.readonlySession(Version.snapshot(SnapshotId.of("GQQFH5G3AXKWZR5H33M0")));
repo.readonlySession(Version.asOf("main", Instant.parse("2026-03-01T00:00:00Z")));
```

- `Version.asOf` picks the newest snapshot on the branch committed at or before that time.
- `repo.resolveVersion(version)` returns the snapshot a version refers to without opening a session.

To read arrays rather than keys, wrap the session in an `IcechunkZarrStore` and use zarr-java:

```java
Array temperature = Array.open(new IcechunkZarrStore(session).resolve("temperature"));
ucar.ma2.Array firstStep = temperature.read(new long[] {0, 0, 0}, new long[] {1, 721, 1440});
```

Or open the session with N5 through `IcechunkKeyValueAccess` and n5-zarr's Zarr v3 classes. The [N5](n5.md) page covers
this route.

```java
N5Reader n5 = new ZarrV3KeyValueReader(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
DatasetAttributes attributes = n5.getDatasetAttributes("em/raw");
DataBlock<?> chunk = n5.readChunk("em/raw", attributes, 0, 0);
```

## Write and commit

A writable session collects changes until you commit them. After a commit the session is read-only; open a new one
for more changes.

```java
try (Session session = repo.writableSession("main")) {
    // write through session.store() or an IcechunkZarrStore
    Diff pending = session.status();  // the uncommitted changes
    SnapshotId id = session.commit("Add March data");
} catch (ConflictException e) {
    // someone else committed to main first: open a new session and redo the writes
}
```

`CommitOptions` attaches metadata to the snapshot, or lets a commit with no changes succeed:

```java
session.commit("Add March data", CommitOptions.builder().metadata("source", "era5").build());
```

With zarr-java, wrap the writable session in an `IcechunkZarrStore` and write arrays as usual:

```java
try (Session session = repo.writableSession("main")) {
    IcechunkZarrStore store = new IcechunkZarrStore(session);
    Group.create(store.resolve());
    Array array = Array.create(
            store.resolve("my_array"),
            Array.metadataBuilder()
                    .withShape(10)
                    .withDataType(DataType.INT32)
                    .withChunkShape(5)
                    .withFillValue(0)
                    .build());
    array.write(data);
    session.commit("Add my_array");
}
```

Metadata values are JSON values: strings, booleans, numbers, lists, maps with string keys, and null.

## Large values and memory

- `Store.get` copies each value into a new `byte[]` and frees icechunk's copy before returning, so reading through many
  chunks keeps memory bounded.
- `Store.getPartialValues(keys)` fetches many values in one call, concurrently. Use it for object storage, where each
  separate `get` pays a network round trip.
- `Store.set(key, buffer)` takes a `ByteBuffer`. Wrap arrays with `ByteBuffer.wrap`. A direct buffer of more than
  64 KiB is read in place, without copying; do not modify it afterwards.

[DESIGN.md](https://github.com/earth-mover/icechunk-java/blob/main/dev/DESIGN.md#bytes) has the measurements behind
these choices.

## History, branches and tags

```java
for (SnapshotInfo snapshot : repo.ancestry(Version.branch("main"))) {
    System.out.println(snapshot.id() + " " + snapshot.writtenAt() + " " + snapshot.message() + " " + snapshot.metadata());
}

SnapshotId tip = repo.lookupBranch("main");
repo.createBranch("experiment", tip);
repo.createTag("v1.0", tip);

SnapshotId parent = repo.lookupSnapshot(tip).parentId().get();
Diff lastCommit = repo.diff(Version.snapshot(parent), Version.branch("main"));
repo.resetBranch("experiment", parent, tip);  // ConflictException unless experiment still points to tip
```

`diff(from, to)` lists the changes made after `from` up to `to`, so `from` must be an earlier snapshot in `to`'s
history.

## Expire snapshots and collect garbage

- **Expiration** removes old snapshots from every history.
- **Garbage collection** then deletes the objects nothing leads to any more: snapshots expiration released, snapshots
  a `resetBranch` moved away from, and the manifests, chunks and transaction logs only they used.

```java
Instant monthAgo = Instant.now().minus(Duration.ofDays(30));
ExpireResult expired = repo.expireSnapshots(monthAgo);   // keeps every branch and tag tip
repo.expireSnapshots(monthAgo, ExpireOptions.builder().deleteExpiredBranches(true).build());

GcSummary dryRun = repo.garbageCollect(GcOptions.builder()
        .deleteObjectsOlderThan(monthAgo)
        .dryRun(true)
        .build());
GcSummary deleted = repo.garbageCollect(monthAgo);
```

- Each kind of object has its own cutoff in `GcOptions`, and `extraRoots` keeps snapshots that no branch or tag leads
  to.
- A session's chunks are unreachable until it commits, so choose cutoffs earlier than the start of any session still
  writing.
- Readers working while either operation runs can see inconsistent histories.

## Closing and threads

- **Closing.** `Storage`, `Repository`, `Session` and `Store` hold native resources, so close them, ideally with
  try-with-resources. Closing a session closes its store. A repository keeps working after its storage is closed, and
  a session keeps working after its repository is closed.
- **Threads.** All four classes are safe to use from several threads. Each call runs on the calling thread and
  returns when icechunk finishes; calls in progress do not respond to `Thread.interrupt()`.
- **Errors.** Errors from icechunk are thrown as `IcechunkException`, and a lost commit race as its subclass
  `ConflictException`. A string passed as a store key that icechunk cannot parse as one, such as a group's path,
  throws `IllegalArgumentException`. Using a closed object throws `IllegalStateException`.

## Limitations

- Nothing is published to Maven Central. Jars come from GitHub releases or a source build.
- zarr-java covers numeric data types only. It cannot read float16, complex, string or datetime arrays, so many
  xarray-written repositories have arrays that `IcechunkZarrStore` can open as keys but zarr-java cannot decode.
- There is no rebase, node move, or writing of virtual references yet. Repository configuration is passed as a JSON
  document rather than through typed builders.
- Credentials are fixed: they cannot refresh through a Java callback. The native HTTP client does not use
  the JVM's proxy settings or trust store.
- Calls block. Each call occupies the calling thread until icechunk finishes, and there is no
  `CompletableFuture` API. For concurrency, use a thread pool, or `Store.getPartialValues` to fetch many keys in one
  call. A virtual thread holds its carrier thread for the length of a call.
- Logs go to standard error. `Logging.initialize()` turns on icechunk's own log output, filtered by the
  `ICECHUNK_LOG` environment variable; it is not forwarded to a Java logging framework.
