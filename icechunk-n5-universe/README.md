# icechunk-n5-universe

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

This module lets [n5-universe](https://github.com/saalfeldlab/n5-universe) open [icechunk](https://icechunk.io)
repositories from a URL. n5-universe is a library of the [N5](https://github.com/saalfeldlab/n5) family whose
`N5Factory` turns a location string into an N5 reader or writer. [Fiji](https://fiji.sc)'s N5/Zarr importer,
BigDataViewer's N5 viewer and [Paintera](https://github.com/saalfeldlab/paintera) open containers through it. With
this jar on the classpath, `N5Factory` also opens URLs such as:

```
s3://bucket/repo|icechunk:@branch.main/em/raw
gs://bucket/repo|icechunk:@tag.v1
/local/repo|icechunk:@tag.v1
/local/repo|icechunk:@GQQFH5G3AXKWZR5H33M0/labels
/local/repo.icechunk
```

The part before `|` is the repository: `s3://`, `gs://`, `http(s)://`, `file://` or a local path. The `icechunk:`
stage names a branch, tag or snapshot, followed by an optional path to a group or array. Without a version it opens
the `main` branch. A `RepositoryResolver` on the classpath can claim other locations: the separate
icechunk-arraylake-java project provides one that opens Arraylake repositories by name, such as `al:org/repo` or
`https://app.earthmover.io/org/repo`.

Use it to open icechunk repositories from N5 applications that take a URL, without changing those applications.

## Requires an unreleased n5-universe

The `KeyValueAccessProvider` interface this module implements is not in any n5-universe release. The module compiles
against an n5-universe 3.1.1-SNAPSHOT that has it, from the
[`kva-provider` branch of ianhi/n5-universe](https://github.com/ianhi/n5-universe/tree/kva-provider), installed into
your local Maven repository. [Changes in other projects](../docs/upstream.md) lists what else depends on that branch
and on an n5-ij branch for Fiji.
The default build skips this module; build it with the Maven profile `-Pn5-universe-provider`:

```sh
pixi run build-native
pixi run mvn -B install -DskipTests -Pn5-universe-provider -pl icechunk-n5-universe -am
```

At run time the application must also use that n5-universe build.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-n5-universe</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

It depends on [icechunk-n5](../icechunk-n5) and [icechunk-java](../icechunk-java). n5 and n5-universe are `provided`:
the application brings them. n5-universe finds the provider through `META-INF/services`, so no code registers it.

## Example

```java
N5Reader n5 = new N5Factory().openReader("s3://bucket/repo|icechunk:@branch.main/em");
DatasetAttributes attributes = n5.getDatasetAttributes("raw");
```

## Behavior

- URLs open read-only. `openWriter` throws, because a URL has no place for a commit. To write, use
  `IcechunkKeyValueAccess` on a writable session, as in [icechunk-n5](../icechunk-n5).
- Each repository is opened once and kept open. Each URL opens a new session, so a branch is read at its tip when the
  URL is opened.
- S3 and Google Cloud Storage repositories open with the environment's credentials, such as the AWS default chain,
  and anonymously if that fails. An S3 bucket's region is looked up from the bucket.
- Virtual chunks are read from every container the repository declares, anonymously, so no credentials are sent to a
  location the repository names. Containers on the local file system are never authorized. Set the system property
  `icechunk.virtualChunks=none` to authorize none.
- Large reads are split into parallel ranged requests of 2 MiB, overriding the repository's setting. Set the system
  property `icechunk.requestSize` to another number of bytes to change it.
- A failed request is tried up to 10 times, waiting from 100 ms up to 10 s between tries.

## More

- [docs/n5.md](../docs/n5.md#open-from-a-url) covers opening from a URL alongside the rest of N5 over icechunk.
- The javadoc of `IcechunkKeyValueAccessProvider` and `IcechunkUrl` gives the full URL syntax and open behavior.
