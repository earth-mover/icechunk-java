# icechunk-java

!!! warning "Experimental, not officially supported"
    This is not an Earthmover product and is not part of the icechunk project's supported surface. Nothing is
    published to Maven Central, the API will change without notice, and there is no guarantee of fixes or
    compatibility. Do not use it for data you cannot afford to lose.

icechunk-java lets Java programs read and write [icechunk](https://icechunk.io) repositories, the same repositories
Python users work with through [icechunk-python](https://pypi.org/project/icechunk/). An icechunk repository holds
[Zarr](https://zarr.dev) arrays with version control: commits, branches, tags, and reading the data as it was at an
earlier snapshot or point in time. A repository can also hold virtual chunks, which point into existing files, such
as TIFF, NetCDF or HDF5 files in a bucket, so the data is read from where it already lives instead of being copied.
With icechunk-java, zarr-java and N5 code, and Fiji tools such as BigDataViewer, can open these repositories directly.

The bindings call the icechunk Rust library through JNI, so they read and write the same data as icechunk-python.

## Use it

### From Java code

Open a repository, start a session on a version, and read Zarr keys from the session's `Store`:

```java
Storage local = Storage.localFilesystem(Paths.get("/data/my-repo"));
Repository repo = Repository.open(local);

try (Session session = repo.readonlySession(Version.branch("main"))) {
    Store store = session.store();
    List<String> children = store.listDir("");
    Optional<byte[]> metadata = store.get("temperature/zarr.json");
}

repo.readonlySession(Version.tag("v1.0"));
repo.readonlySession(Version.asOf("main", Instant.parse("2026-03-01T00:00:00Z")));
```

A writable session collects changes until `session.commit(message)` makes them a new snapshot. The repository's
`README.md`, under "Using the API", covers storage on S3, Google Cloud Storage and Azure, writing and committing,
branches, tags, history and garbage collection.

### With zarr-java

`icechunk-zarr-java` wraps a session as a [zarr-java](https://github.com/zarr-developers/zarr-java) store, so
zarr-java's `Array` and `Group` work on a version of the repository:

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

zarr-java reads numeric data types only, so it cannot decode float16, complex, string or datetime arrays.

### With N5

`icechunk-n5` provides `IcechunkKeyValueAccess`, which lets [n5-zarr](https://github.com/saalfeldlab/n5-zarr)'s
Zarr v3 reader and writer work on a session:

```java
--8<-- "N5Basics.java:write"
```

This works with released n5, n5-zarr and n5-universe. The [N5](n5.md) page covers reading older versions, opening
through n5-universe's `N5Factory`, and keeping one writer across commits.

### In Fiji and BigDataViewer

With the `icechunk-n5-universe` module, Fiji's importer (**File > Import > HDF5/N5/Zarr/OME-NGFF ...**) and
BigDataViewer's N5 viewer (**Plugins > BigDataViewer > HDF5/N5/Zarr/OME-NGFF Viewer**) open a repository from a URL
typed into their dataset dialog:

```text
/data/repo|icechunk:@branch.main
s3://bucket/repo|icechunk:@tag.v1/em/raw
https://app.earthmover.io/org/repo
```

The part before `|` is the repository's location. The `icechunk:` stage names a branch, tag or snapshot, followed by
an optional path inside the repository. An Arraylake repository opens by its web address, or as `al:org/repo`, and
needs the separate icechunk-arraylake-java project on the classpath. URLs open read-only.

Fiji needs builds of n5-universe and n5-ij that no release contains yet, in place of the ones it ships.
[Use in Fiji](fiji.md) builds them and the icechunk-java jars and installs all six into Fiji.
[Changes in other projects](upstream.md) lists the changes.
[Open from a URL](n5.md#open-from-a-url) has the full URL syntax and how credentials are found.

## Modules

| Artifact | What it contains |
|---|---|
| `icechunk-java` | `Storage`, `Repository`, `Session` and `Store`. |
| `icechunk-zarr-java` | `IcechunkZarrStore`, for [zarr-java](https://github.com/zarr-developers/zarr-java). |
| `icechunk-n5` | `IcechunkKeyValueAccess`, for [N5](https://github.com/saalfeldlab/n5) and [n5-zarr](https://github.com/saalfeldlab/n5-zarr). See [N5](n5.md). |
| `icechunk-n5-codecs` | The `numcodecs.pcodec` and `numcodecs.zlib` codecs for n5-zarr. See [N5](n5.md#numcodecs-codecs). |
| `icechunk-n5-universe` | `IcechunkKeyValueAccessProvider`, which opens repositories from URLs through n5-universe's `N5Factory`. Builds only with `-Pn5-universe-provider`. |

Nothing is on Maven Central. Tagged releases of the repository attach the jars to a GitHub release, with the native
library bundled. [Get the jars](n5.md#get-the-jars) shows how to build and install them from source.

## More

- [How the pieces fit](ecosystem.md) explains where these modules sit among icechunk, Zarr, N5 and Fiji, and which
  modules each goal needs.
- [N5](n5.md) covers the N5 adapter, URLs, the numcodecs codecs, and how icechunk differs from a file system.
- [Use in Fiji](fiji.md) builds and installs the jars Fiji needs to open repositories by URL.
- [Changes in other projects](upstream.md) lists the n5-universe and n5-ij changes that opening by URL depends on.
- The repository's `README.md` documents the Java API: storage, repositories, sessions, branches, tags and garbage
  collection.
