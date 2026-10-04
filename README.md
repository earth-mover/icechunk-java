# icechunk-java

> [!WARNING]
> Experimental and not officially supported. This is not an Earthmover product and is not part of the icechunk
> project's supported surface. Nothing is published to Maven Central, the API will change without notice, and there
> is no guarantee of fixes or compatibility. Do not use it for data you cannot afford to lose.

Java bindings for [icechunk](https://icechunk.io), plus connectors that let Java's Zarr and N5 tools use them.

- Read and write icechunk repositories from Java: the same repositories, branches, tags and commits as
  [icechunk-python](https://pypi.org/project/icechunk/), through icechunk's Rust library over JNI.
- Use a session as a [zarr-java](https://github.com/zarr-developers/zarr-java) store, through `IcechunkZarrStore`.
- Open repositories by URL from N5 code, Fiji and BigDataViewer, and save Fiji images into them with
  **File > Save As > icechunk...** ([Use in Fiji](docs/fiji.md)).
- Add the pcodec and zlib codecs to n5-zarr, which lacks them.

For what icechunk itself offers, such as version control for Zarr arrays and virtual chunks that read existing files
in place, see the [icechunk docs](https://icechunk.io).

Write an array with zarr-java and commit it:

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
| `icechunk-java` | The core API: `Storage`, `Repository`, `Session` and `Store`, with the native library. |
| `icechunk-zarr-java` | `IcechunkZarrStore`, a store for zarr-java. |
| `icechunk-n5` | `IcechunkKeyValueAccess`, through which N5 and n5-zarr read and write a session. |
| `icechunk-n5-universe` | Opens repositories by URL through n5-universe's `N5Factory`, as Fiji's importer and BigDataViewer do, and adds Fiji's **File > Save As > icechunk...**. Needs the n5-universe fork, so it builds only with `-Pn5-universe-provider`. |
| `icechunk-n5-codecs` | The `numcodecs.pcodec` and `numcodecs.zlib` codecs, for n5-zarr on any store. |
| `examples` | Runnable programs. Not published. |

zarr-java, n5 and n5-zarr are `provided` dependencies, so you choose their versions. Code that only moves Zarr keys
and bytes needs only `icechunk-java`.

[How the pieces fit](docs/ecosystem.md) explains where these modules sit among icechunk, Zarr, N5 and Fiji.

## Getting the jars

Nothing is published to Maven Central. Tagged releases of this repository attach jars to a GitHub release, with the
native library for Linux (x86_64, aarch64), macOS (x86_64, arm64) and Windows (x86_64) bundled inside `icechunk-java`.
Put `icechunk-java` and the adapter you use on the classpath, plus zarr-java or n5 and n5-zarr.

## Building

To build from source you need:

- [pixi](https://pixi.sh), which provides JDK 21 and Maven.
- [rustup](https://rustup.rs), which installs the pinned Rust toolchain on first build.

```sh
pixi run test                    # build the native library, then build and test the jars
pixi run example                 # run examples/.../Quickstart.java
pixi run example ReadPublicData  # read ERA5 data from a public S3 bucket
```

### Using source-built jars

- The jars run on Java 8, so libraries such as [n5-ij](https://github.com/saalfeldlab/n5-ij) and
  [n5-universe](https://github.com/saalfeldlab/n5-universe) can depend on them. The build itself needs JDK 21.
- `icechunk-java` is a multi-release jar. On Java 9 and later it loads newer versions of a few internal classes from
  `META-INF/versions/` ([JEP 238](https://openjdk.org/jeps/238)), with no API change. An object you forget to close is
  then released by a [`Cleaner`](https://docs.oracle.com/javase/9/docs/api/java/lang/ref/Cleaner.html) once
  unreachable; on Java 8 it stays open until the JVM exits. If you shade the jar into an uber-jar, **keep
  `Multi-Release: true`** in the merged manifest, or every JVM gets the Java 8 classes
  ([DESIGN.md](dev/DESIGN.md#handles)).
- A development build loads the native library from `native/target/debug`, through the `icechunk.native.dir`
  system property. Set the same property to use source-built jars from your own project, for example
  `-Dicechunk.native.dir=/path/to/icechunk-java/native/target/release`. Release jars bundle the library and need no
  property ([DESIGN.md](dev/DESIGN.md#packaging)).

## Documentation

The [docs site](https://earth-mover.github.io/icechunk-java/) is built from the Markdown files in `docs/`:

- [docs/java-api.md](docs/java-api.md): the Java API. Storage backends, sessions, writing and committing, branches,
  tags, history, garbage collection, threads, and limitations.
- [docs/n5.md](docs/n5.md): the N5 adapter, opening repositories by URL, and the numcodecs codecs.
- [docs/fiji.md](docs/fiji.md): building and installing the jars Fiji needs.
- [docs/ecosystem.md](docs/ecosystem.md): where the modules sit among icechunk, Zarr, N5 and Fiji.
- [docs/upstream.md](docs/upstream.md): the n5-universe and n5-ij changes that opening by URL depends on.
- [docs/stardist.md](docs/stardist.md) and [docs/write-back.md](docs/write-back.md): worked Fiji examples.

## Further reading

- [DESIGN.md](dev/DESIGN.md) explains how the binding works and why it is built this way.
- [CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the checks, and how to add a native method.
- The [icechunk documentation](https://icechunk.io) explains repositories, sessions and version control in depth.
