# icechunk-java

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

The core of the Java bindings for [icechunk](https://icechunk.io), the transactional storage engine for
[Zarr](https://zarr.dev). It opens icechunk repositories, starts sessions on branches, tags or snapshots, and commits
new versions. A session's `Store` maps Zarr keys, such as `temperature/zarr.json` or `temperature/c/0/0`, to bytes.
The work is done by the icechunk Rust library, which this jar calls through JNI.

Use this module on its own when your code reads and writes Zarr keys directly, for example an adapter for a Zarr
library that this repository does not cover. To work with arrays, add [icechunk-zarr-java](../icechunk-zarr-java) for
zarr-java or [icechunk-n5](../icechunk-n5) for N5.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The jar has no Java dependencies. It targets Java 8, and is a multi-release jar with Java 9 versions of a few
internal classes.

It needs the `icechunk_jni` native library. Release jars from GitHub bundle it for Linux, macOS and Windows. A source
build does not, so point the JVM at the build output:

```sh
java -Dicechunk.native.dir=/path/to/icechunk-java/native/target/debug ...
```

The javadoc of `NativeLoader` lists every place the library is looked for.

## Example

Write the root group's metadata, commit, and read it back from the branch:

```java
try (Storage storage = Storage.inMemory();
        Repository repo = Repository.create(storage)) {
    try (Session session = repo.writableSession("main")) {
        String group = "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}";
        session.store().set("zarr.json", ByteBuffer.wrap(group.getBytes(StandardCharsets.UTF_8)));
        session.commit("Add the root group");
    }
    try (Session session = repo.readonlySession(Version.branch("main"))) {
        Optional<byte[]> metadata = session.store().get("zarr.json");
    }
}
```

A store holds only Zarr metadata documents and chunks, so setting any other key fails.

## Contents

| Class | What it is |
|---|---|
| `Storage` | Where a repository lives: local directory, memory, S3, Google Cloud Storage, Azure, or HTTP for reading. |
| `Repository` | Branches, tags, history, diffs, snapshot expiration and garbage collection. Opens sessions. |
| `Session` | One version to read, or uncommitted changes on a branch. `commit` makes a new snapshot. |
| `Store` | The Zarr key/value view of a session. |
| `Version` | A branch, tag, snapshot, or a branch as of a point in time. |
| `Pcodec` | Decodes the [pcodec](https://github.com/pcodec/pcodec) format, for [icechunk-n5-codecs](../icechunk-n5-codecs). |
| `NativeExtensions` | Wraps objects created by a library that adds its own native code, such as an Arraylake client. |

`Storage`, `Repository`, `Session` and `Store` hold native resources, so close them, ideally with try-with-resources.

## More

- [README.md](../README.md#using-the-api) walks through the API: storage backends, reading versions, commits,
  branches and tags, garbage collection, threads and errors.
- [DESIGN.md](../DESIGN.md) explains how the JNI layer, handles and native library loading work.
