# icechunk-zarr-java

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

`IcechunkZarrStore` lets [zarr-java](https://github.com/zarr-developers/zarr-java), a Java implementation of
[Zarr](https://zarr.dev), read and write arrays in an [icechunk](https://icechunk.io) repository. It implements
zarr-java's `Store` over an icechunk session, so zarr-java's `Array` and `Group` classes work on a version of the
repository the way they work on a directory or a bucket.

Use it from a Java program that already uses zarr-java, or that wants Zarr arrays with version control: commits,
branches, tags, and reading any earlier version.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-zarr-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>dev.zarr</groupId>
  <artifactId>zarr-java</artifactId>
  <version>0.3.1</version>
</dependency>
```

It depends on [icechunk-java](../icechunk-java), which comes in transitively and needs the native library described
there. zarr-java is a `provided` dependency, so you add it yourself and choose its version; this module is built
against 0.3.1.

## Example

From [`Quickstart.java`](../examples/src/main/java/io/earthmover/icechunk/examples/Quickstart.java): create an array,
write it, commit, then read the first version back after a second commit.

```java
SnapshotId first;
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
    array.write(ints(10, 1));
    first = session.commit("first commit");
}

// ... a second session overwrites part of the array and commits ...

try (Session earlier = repo.readonlySession(Version.snapshot(first))) {
    Array array = Array.open(new IcechunkZarrStore(earlier).resolve("my_array"));
    ucar.ma2.Array values = array.read();
}
```

Run the whole program with `pixi run example Quickstart` from the repository root.

The store does not own the session: close the session yourself, and commit through it to save writes.

zarr-java reads numeric data types only, so arrays of strings, float16, complex numbers or datetimes, common in
repositories written with xarray, open as keys but cannot be decoded.

## More

- [README.md](../README.md) covers the icechunk API: storage backends, versions, branches, tags and commits.
- [DESIGN.md](../dev/DESIGN.md#zarr-java-adapter) describes how the adapter maps zarr-java's calls to the store.
