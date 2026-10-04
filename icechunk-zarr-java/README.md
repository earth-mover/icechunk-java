# icechunk-zarr-java

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

`IcechunkZarrStore` lets [zarr-java](https://github.com/zarr-developers/zarr-java) read and write arrays in an
[icechunk](https://icechunk.io) repository.

- It is a zarr-java `Store` over one session: zarr-java's `Array` and `Group` work on a version of the repository as
  they do on a directory or a bucket.
- The store does not own the session: commit through the session to save writes, and close it
  yourself.
- zarr-java reads numeric data types only. It cannot decode string, float16, complex or datetime arrays, which are
  common in repositories written with xarray. See [Limitations](../docs/java-api.md#limitations).

Repositories, versions, branches and commits are covered in [Java API](../docs/java-api.md).

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

- [icechunk-java](../icechunk-java) comes in transitively, and needs the native library described there.
- zarr-java is `provided`, so you add it and choose its version. This module is built against 0.3.1.

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

## More

- [Java API](../docs/java-api.md) covers storage backends, versions, branches, tags and commits.
- [DESIGN.md](../dev/DESIGN.md#zarr-java-adapter) describes how the adapter maps zarr-java's calls to the store.
