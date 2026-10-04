# icechunk-n5-universe

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

**Opens [icechunk](https://icechunk.io) repositories by URL in
[n5-universe](https://github.com/saalfeldlab/n5-universe), and saves Fiji images into them.**

- **Open by URL.** With this jar on the classpath, `N5Factory` opens URLs such as
  `s3://bucket/repo|icechunk://branch.main/em/raw`. So do the tools built on it: [Fiji](https://fiji.sc)'s N5/Zarr
  importer, BigDataViewer's N5 viewer and [Paintera](https://github.com/saalfeldlab/paintera). URLs open
  **read-only**.
- **Save from Fiji.** **File > Save As > icechunk...** saves the current image as OME-Zarr into a branch and commits
  it.
- **Needs an unreleased n5-universe**, so the default build skips this module.

[Open from a URL](../docs/n5.md#open-from-a-url) gives the URL syntax, credentials and what the provider does.
[Use in Fiji](../docs/fiji.md) installs it into Fiji.

## Build

The `KeyValueAccessProvider` interface this module implements is in no n5-universe release. The module compiles
against n5-universe 3.1.1-SNAPSHOT from the
[`kva-provider` branch of ianhi/n5-universe](https://github.com/ianhi/n5-universe/tree/kva-provider). Install that
branch into your local Maven repository, as in [Build the jars](../docs/fiji.md#build-the-jars), then build with the
`-Pn5-universe-provider` profile:

```sh
pixi run build-native
pixi run mvn -B install -DskipTests -Pn5-universe-provider -pl icechunk-n5-universe -am
```

At run time the application must also use that n5-universe build. [Changes in other projects](../docs/upstream.md)
lists what the fork changes, and the n5-ij fork that Fiji's URL dialog needs.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-n5-universe</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

- [icechunk-n5](../icechunk-n5) and [icechunk-java](../icechunk-java) come in transitively.
- n5 and n5-universe are `provided`: the application brings them.
- n5-universe finds the provider through `META-INF/services`, so no code registers it.

## Example

```java
N5Reader n5 = new N5Factory().openReader("s3://bucket/repo|icechunk://branch.main/em");
DatasetAttributes attributes = n5.getDatasetAttributes("raw");
```

To write, use `IcechunkKeyValueAccess` on a writable session, as in [icechunk-n5](../icechunk-n5).

## Save As in Fiji

**File > Save As > icechunk...** (`SaveToIcechunk`) writes through n5-ij's OME-Zarr exporter, so the metadata, pyramid
and compression match Fiji's own export.

- A missing branch is created at the tip of `main`, and deleted again if the save fails.
- Nothing is committed if the save fails.
- A new repository is created only at a local path, and only when **Create repository if missing** is checked.

## More

- The javadoc of `IcechunkKeyValueAccessProvider` and `IcechunkUrl` gives the full URL syntax and open behavior.
- [Example: save results into icechunk](../docs/write-back.md) uses Save As end to end.
