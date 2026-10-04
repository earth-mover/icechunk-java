# icechunk-java

!!! warning "Experimental, not officially supported"
    This is not an Earthmover product and is not part of the icechunk project's supported surface. Nothing is
    published to Maven Central, the API will change without notice, and there is no guarantee of fixes or
    compatibility. Do not use it for data you cannot afford to lose.

    Interested in official support? [Open a GitHub issue](https://github.com/earth-mover/icechunk-java/issues)
    and say what you would use it for.

Java bindings for [icechunk](https://icechunk.io), plus connectors that let zarr-java, N5 and Fiji read and write
icechunk repositories. The bindings call icechunk's Rust library through JNI, so they read and write the same
repositories as [icechunk-python](https://pypi.org/project/icechunk/).

For what icechunk itself offers, such as version control for Zarr arrays and virtual chunks that read existing TIFF,
NetCDF or HDF5 files in place, see the [icechunk docs](https://icechunk.io).

Open a repository, start a session on a version, and read Zarr keys from the session's `Store`:

```java
Storage local = Storage.localFilesystem(Paths.get("/data/my-repo"));
Repository repo = Repository.open(local);

try (Session session = repo.readonlySession(Version.branch("main"))) {
    Store store = session.store();
    List<String> children = store.listDir("");
    Optional<byte[]> metadata = store.get("temperature/zarr.json");
}
```

## Where to go next

| To | Read |
|---|---|
| See which modules and jars you need | [How the pieces fit](ecosystem.md) |
| Open repositories, read versions, write and commit, and manage history from Java, or with zarr-java | [Java API](java-api.md) |
| Read and write with N5 and n5-zarr, or open repositories by URL | [N5](n5.md) |
| Open and save images in Fiji and BigDataViewer | [Use in Fiji](fiji.md) |
| Follow a worked example in Fiji | [Segment nuclei with StarDist](stardist.md), [Save results into icechunk](write-back.md) |
| See the n5-universe and n5-ij changes that opening by URL depends on | [Changes in other projects](upstream.md) |

## Get the jars

Nothing is on Maven Central, and no release is tagged yet, so build the jars from source:
[Get the jars](n5.md#get-the-jars) installs them into your local Maven repository, and
[Use in Fiji](fiji.md#build-the-jars) builds the set Fiji needs. Tagged releases will attach the jars to a GitHub
release, with the native library bundled.
