# icechunk-java

> [!WARNING]
> **EXPERIMENTAL - NOT OFFICIALLY SUPPORTED.**
> This is not an Earthmover product and is not part of the icechunk project's supported surface. Nothing is
> published to Maven Central, the API will change without notice, and there is no guarantee of fixes or
> compatibility. Do not use it for data you cannot afford to lose.

Java bindings for [icechunk](https://icechunk.io), the transactional storage engine for Zarr. They call the icechunk
Rust library through JNI, so Java programs get the same repositories, branches, tags and commits as icechunk-python,
and can read and write the same data.

```java
try (Storage storage = Storage.localFilesystem(Paths.get("/tmp/my-repo"));
        Repository repo = Repository.create(storage);
        Session session = repo.writableSession("main")) {

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

    SnapshotId id = session.commit("first commit");
}
```

## Modules

| Artifact | What it contains |
|---|---|
| `icechunk-java` | `Storage`, `Repository`, `Session` and `Store`. No runtime dependencies besides the native library. |
| `icechunk-zarr-java` | `IcechunkZarrStore`, which lets [zarr-java](https://github.com/zarr-developers/zarr-java) read and write arrays in a session. zarr-java itself is a `provided` dependency, so you choose its version. |
| `examples` | Runnable programs. Not published. |

Programs that only move Zarr keys and bytes, such as a store adapter for another library, need only
`icechunk-java`. Programs that work with arrays use `icechunk-zarr-java` with zarr-java.

## Getting the jars

Nothing is published to Maven Central. Tagged releases of this repository attach jars to a GitHub release, with the
native library for Linux (x86_64, aarch64), macOS (x86_64, arm64) and Windows (x86_64) bundled inside `icechunk-java`.
Put both jars on the classpath, plus zarr-java if you use `icechunk-zarr-java`.

## Building

To build from source you need:

- [pixi](https://pixi.sh), which provides JDK 21 and Maven;
- [rustup](https://rustup.rs). The pinned Rust toolchain installs on first build.

```sh
pixi run test                    # build the native library, then build and test the jars
pixi run example                 # run examples/.../Quickstart.java
pixi run example ReadPublicData  # read ERA5 data from a public S3 bucket
```

The jars run on Java 8 or later. The build itself needs JDK 21.

A development build loads the native library from `native/target/debug`, which the Maven build points to with the
`icechunk.native.dir` system property. Set the same property when you use the jars from your own project, for
example `-Dicechunk.native.dir=/path/to/icechunk-java/native/target/release`. Release jars bundle the library and
need no property; see [DESIGN.md](DESIGN.md#packaging).

## Using the API

### Opening a repository

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

### Reading a version

A session reads one version of the repository: the tip of a branch, a tag, or a snapshot.

```java
try (Session session = repo.readonlySession(Version.branch("main"))) {
    Store store = session.store();
    List<String> children = store.listDir("");
    Optional<byte[]> metadata = store.get("temperature/zarr.json");
    Optional<byte[]> lastBytes = store.get("temperature/c/0/0", ByteRange.suffix(16));
}

repo.readonlySession(Version.tag("v1.0"));
repo.readonlySession(Version.snapshot(SnapshotId.of("GQQFH5G3AXKWZR5H33M0")));
```

`Store` works with raw Zarr keys. To read arrays, wrap the session in an `IcechunkZarrStore` and use zarr-java:

```java
Array temperature = Array.open(new IcechunkZarrStore(session).resolve("temperature"));
ucar.ma2.Array firstStep = temperature.read(new long[] {0, 0, 0}, new long[] {1, 721, 1440});
```

### Writing and committing

A writable session collects changes until you commit them. After a commit the session is read-only; open a new one
for more changes.

```java
try (Session session = repo.writableSession("main")) {
    // write through session.store() or an IcechunkZarrStore
    SnapshotId id = session.commit("Add March data");
} catch (ConflictException e) {
    // someone else committed to main first: open a new session and redo the writes
}
```

### Large values and memory

- `Store.get` copies each value into a new `byte[]` and frees icechunk's copy before returning, so reading through many
  chunks keeps memory bounded.
- `Store.getPartialValues(keys)` fetches many values in one call, concurrently. Use it for object storage, where each
  separate `get` pays a network round trip.
- `Store.set(key, buffer)` takes a `ByteBuffer`. Wrap arrays with `ByteBuffer.wrap`. A direct buffer of more than
  64 KiB is read in place, without copying; do not modify it afterwards.

[DESIGN.md](DESIGN.md#bytes) has the measurements behind these choices.

### History, branches and tags

```java
for (SnapshotInfo snapshot : repo.ancestry(Version.branch("main"))) {
    System.out.println(snapshot.id() + " " + snapshot.writtenAt() + " " + snapshot.message());
}

SnapshotId tip = repo.lookupBranch("main");
repo.createBranch("experiment", tip);
repo.createTag("v1.0", tip);
```

### Closing and threads

`Storage`, `Repository`, `Session` and `Store` hold native resources. Close them, ideally with try-with-resources.
Closing a session closes its store. A repository keeps working after its storage is closed, and a session keeps
working after its repository is closed.

All four classes are safe to use from several threads. Each call runs on the calling thread and returns when
icechunk finishes; calls in progress do not respond to `Thread.interrupt()`.

Errors from icechunk are thrown as `IcechunkException`, and a lost commit race as its subclass `ConflictException`.
Using a closed object throws `IllegalStateException`.

## Limitations

- **Not on Maven Central.** Jars come from GitHub releases or a source build.
- **zarr-java covers numeric data types only.** It cannot read float16, complex, string or datetime arrays, so many
  xarray-written repositories have arrays that `IcechunkZarrStore` can open as keys but zarr-java cannot decode.
- **Missing APIs.** There is no diff, garbage collection, snapshot expiration, rebase, node move, or writing of
  virtual references yet, and repository configuration is passed as a JSON document rather than typed builders.
- **Fixed credentials only.** Credentials cannot refresh through a Java callback. The native HTTP client does not use
  the JVM's proxy settings or trust store.
- **Blocking calls only.** There is no `CompletableFuture` API, and icechunk's log output is not forwarded to a
  Java logging framework.

## Further reading

- [DESIGN.md](DESIGN.md) explains how the binding works and why it is built this way.
- [CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the checks, and how to add a native method.
- The [icechunk documentation](https://icechunk.io) explains repositories, sessions and version control in depth.
  The Java API follows the Python API's names where Java conventions allow.
