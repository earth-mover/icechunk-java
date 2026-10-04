# icechunk-n5

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

**`IcechunkKeyValueAccess` lets [N5](https://github.com/saalfeldlab/n5) read and write an
[icechunk](https://icechunk.io) repository.**

- **An N5 `KeyValueAccess` over one session.** [n5-zarr](https://github.com/saalfeldlab/n5-zarr)'s Zarr v3 reader and
  writer work on a version of a repository as they do on a directory or a bucket.
- **For N5 code**, such as a Fiji plugin or script, or [Paintera](https://github.com/saalfeldlab/paintera).
- **Zarr v3 only.** Use n5-zarr's Zarr v3 classes, or pass `StorageFormat.ZARR3` to `N5Factory`.

[N5](../docs/n5.md) is the guide: reading versions, keeping a writer across commits, and
[how icechunk differs from a file system](../docs/n5.md#how-icechunk-differs-from-a-file-system).

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-n5</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

- [icechunk-java](../icechunk-java) comes in transitively, and needs the native library described there.
- n5 is `provided`, because Fiji and Paintera bring their own. Add n5-zarr yourself, and
  [n5-universe](https://github.com/saalfeldlab/n5-universe) if you open containers through its `N5Factory`.
- All three come from the [SciJava Maven repository](https://maven.scijava.org/content/groups/public), not Maven
  Central. This module is built against n5 4.0.2, n5-zarr 2.0.2 and n5-universe 3.1.0.

## Example

From [`N5Basics.java`](../examples/src/main/java/io/earthmover/icechunk/examples/N5Basics.java): write a dataset with
n5-zarr's Zarr v3 writer and commit it. Paths are relative to the repository root, so the base path is `""`.

```java
try (Session session = repo.writableSession("main")) {
    N5Writer n5 =
            new ZarrV3KeyValueWriter(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
    n5.createGroup("em");
    n5.setAttribute("em", "units", "nm");
    DatasetAttributes attributes = n5.createDataset(
            "em/raw", new long[] {64, 64}, new int[] {32, 32}, DataType.UINT16, new GzipCompression());
    n5.writeChunk(
            "em/raw", attributes, new ShortArrayDataBlock(new int[] {32, 32}, new long[] {0, 0}, fill(1)));
    first = session.commit("Add em/raw");
}
```

Reading works the same way with `ZarrV3KeyValueReader` on a read-only session. Run the whole program with
`pixi run example N5Basics` from the repository root.

## More

- [N5](../docs/n5.md) walks through `N5Basics` and covers the rest of N5 over icechunk.
- [icechunk-n5-universe](../icechunk-n5-universe) opens icechunk URLs in N5 tools such as Fiji's importer.
- [icechunk-n5-codecs](../icechunk-n5-codecs) adds two codecs that zarr-python writes and n5-zarr cannot read.
