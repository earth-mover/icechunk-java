# icechunk-java

!!! warning "Experimental, not officially supported"
    This is not an Earthmover product and is not part of the icechunk project's supported surface. Nothing is
    published to Maven Central, the API will change without notice, and there is no guarantee of fixes or
    compatibility. Do not use it for data you cannot afford to lose.

**Java bindings for [icechunk](https://icechunk.io)**, plus connectors that let Java's Zarr and N5 tools use them.

- **`icechunk-java`**: the bindings. They call icechunk's Rust library through JNI, so they read and write the same
  repositories as [icechunk-python](https://pypi.org/project/icechunk/).
- **`icechunk-zarr-java`**: a store for [zarr-java](https://github.com/zarr-developers/zarr-java).
- **`icechunk-n5`** and **`icechunk-n5-universe`**: N5 connectors. N5 code, Fiji and BigDataViewer open repositories by
  URL, and Fiji saves images into them.
- **`icechunk-n5-codecs`**: the pcodec and zlib codecs, which n5-zarr lacks.

For what icechunk itself offers, such as version control for Zarr arrays and virtual chunks that read existing TIFF,
NetCDF or HDF5 files in place, see the [icechunk docs](https://icechunk.io). [How the pieces fit](ecosystem.md) shows
how the modules connect.

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

A writable session collects changes until `session.commit(message)` makes them a new snapshot. The
[Java API](java-api.md) page covers storage on S3, Google Cloud Storage and Azure, writing and committing, branches,
tags, history and garbage collection.

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
/data/repo|icechunk://branch.main
s3://bucket/repo|icechunk://tag.v1/em/raw
https://app.earthmover.io/org/repo
```

- **Read-only.** URLs open read-only; to save from Fiji, use **File > Save As > icechunk...**.
- **Arraylake** repositories open by web address or as `al:org/repo` with the separate icechunk-arraylake-java jar.
- **Unreleased forks.** Fiji needs builds of n5-universe and n5-ij that no release contains yet.
  [Use in Fiji](fiji.md) installs them with the icechunk-java jars.
- **Syntax and credentials:** [Open from a URL](n5.md#open-from-a-url).

## Get the jars

Nothing is on Maven Central. Tagged releases of the repository attach the jars to a GitHub release, with the native
library bundled. [Get the jars](n5.md#get-the-jars) shows how to build and install them from source.

## More

- [Java API](java-api.md): storage, sessions, commits, branches, tags, garbage collection and limitations.
- [N5](n5.md): the N5 adapter, URLs, the numcodecs codecs, and how icechunk differs from a file system.
- [Use in Fiji](fiji.md): build and install the jars Fiji needs.
- [How the pieces fit](ecosystem.md): where the modules sit among icechunk, Zarr, N5 and Fiji, and which to use.
- [Changes in other projects](upstream.md): the n5-universe and n5-ij changes that opening by URL depends on.
