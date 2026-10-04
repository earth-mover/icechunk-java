# icechunk-java

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

**The core Java API for [icechunk](https://icechunk.io): open a repository, read or write one version of it, and
commit.**

- **Raw Zarr keys.** A session's `Store` maps keys such as `temperature/zarr.json` or `temperature/c/0/0` to bytes.
- **For arrays**, add [icechunk-zarr-java](../icechunk-zarr-java) for zarr-java or [icechunk-n5](../icechunk-n5) for
  N5. Use this module alone when your code moves Zarr keys and bytes itself.
- **Native code.** The icechunk Rust library does the work, called through JNI.

[Java API](../docs/java-api.md) is the guide: storage backends, sessions, commits, branches, garbage collection and
threads.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

- No Java dependencies. Targets Java 8, as a multi-release jar with Java 9 versions of a few internal classes.
- Needs the `icechunk_jni` native library. Release jars from GitHub bundle it for Linux, macOS and Windows. A source
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

`Storage`, `Repository`, `Session` and `Store` hold native resources, so **close them**, ideally with
try-with-resources.

## More

- [Java API](../docs/java-api.md) walks through the API.
- [DESIGN.md](../dev/DESIGN.md) explains how the JNI layer, handles and native library loading work.
