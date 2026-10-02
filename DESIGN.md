# Design

This document explains how icechunk-java connects Java to the icechunk Rust library, and why. It is for people
changing the binding. For using it, see the [README](README.md).

## The decision

The binding is hand-written JNI, using the [`jni`](https://docs.rs/jni) crate (jni-rs 0.22) on the Rust side. The
compiled jars target Java 11.

### Why JNI

The binding has to run on the JVMs its likely users have. Fiji's recommended download bundles Java 21
([downloads](https://imagej.net/software/fiji/downloads)), Paintera builds for Java 25
([pom](https://github.com/saalfeldlab/paintera/blob/master/pom.xml)), and libraries in the SciJava ecosystem, such as
BigDataViewer and N5, compile for Java 8 or 11 through pom-scijava. JNI works on all of them.

Rust libraries with similar needs made the same choice. [Apache OpenDAL](https://github.com/apache/opendal/tree/main/bindings/java),
an object storage library on tokio, and [Lance](https://github.com/lance-format/lance/tree/main/java) both bind to
Java with jni-rs and a tokio runtime. OpenDAL's binding is the model for the runtime and thread handling here.

### Alternatives

- **Java's Foreign Function and Memory API (Panama), with jextract.** It removes the hand-written glue, but it is
  final only from Java 22 ([JEP 454](https://openjdk.org/jeps/454)); on Java 21 it is a preview feature behind
  `--enable-preview`. That rules out Fiji today. It is the natural second backend once the ecosystem is on Java 25.
- **uniffi.** uniffi generates Kotlin, which brings in the Kotlin standard library and JNA and exposes async functions
  as Kotlin `suspend` functions, awkward to call from Java. The third-party
  [uniffi-bindgen-java](https://github.com/IronCoreLabs/uniffi-bindgen-java) generates Java, but on top of FFM, so it
  needs Java 22 as well.
- **JavaCPP over a C API.** This needs a C ABI for icechunk first, then a C++-oriented generator over it. Both layers
  have to manage handles and lifetimes, which is the part JNI already handles here.
- **A C API plus JNA.** This reaches Java 8 and could also serve R, Julia or MATLAB. The cost is a C ABI to design and
  maintain, and JNA's per-call overhead. If a C API for icechunk appears ([icechunk#381](https://github.com/earth-mover/icechunk/issues/381)),
  this option is worth revisiting.

### What JNI costs

Each native method is written by hand, in Rust and in Java. The surface is 36 methods today. Two choices keep
that cost down:

- Configuration crosses the boundary as JSON, not as field-by-field JNI calls (see
  [Configuration as JSON](#configuration-as-json)).
- Every native method follows the same shape (see [Calls](#calls)), so a new method is mostly copying an existing one.

## Layers

```
Java API         Storage  Repository  Session  Store        public classes, io.earthmover.icechunk
                    │         │          │        │
Java internals   NativeHandle (owns a long)   NativeBuffers (lent memory)
                    │
Native           Native.java  ⇄  icechunk_jni (Rust cdylib)
                                    │
                                 handle table ── icechunk objects
                                    │
                                 tokio runtime ── icechunk core
```

`Native.java` declares one static native method per operation. The Rust crate in `native/` implements them, one module
per Java class: `storage.rs`, `repository.rs`, `session.rs`, `store.rs`.

## Calls

A native method runs on the calling Java thread from start to finish:

1. It reads its arguments and looks up the handle.
2. It drives the icechunk future to completion with tokio's `Handle::block_on`, on the calling thread.
3. It converts the result to a Java value and returns it, or throws.

The runtime's own threads only poll I/O and run tasks icechunk spawns internally. They never call into Java.

The alternative is to run each call as a task on a runtime thread and wake the waiting Java thread with the result.
Measured with the benchmarks in `benchmarks/`, that handoff costs about 18 µs per call, and about 50 µs per call with 8
threads, for operations whose own work takes a few microseconds.

Consequences:

- **Calls cannot be interrupted.** The JVM has no way to interrupt a thread running native code, so `Thread.interrupt()`
  takes effect only when the call returns. Timeouts are an open question (see [Open questions](#open-questions)).
- **Exceptions show the caller.** Errors are thrown on the calling thread through `IcechunkException.fromNative`,
  which picks the exception class, so stack traces point at the Java code that made the call.
- **A panic does not crash the JVM.** jni-rs catches panics at every native method and the binding throws them as
  `RuntimeException`.
- **Runtime threads are never attached to the JVM.** So they never keep the JVM from exiting, and never hold a
  reference to the class loader that loaded the binding.

A call made from a runtime thread is rejected with `IllegalStateException`, because blocking there would hold up the
threads that drive the call. No Java code runs on runtime threads today; the check is there for when Java callbacks
arrive.

## Handles

Java never holds a pointer. Each Java object holds a `long` handle: a slot index in a native table plus the slot's
generation number. Every native call looks the handle up and takes a clone of the slot's `Arc` for the duration of the
call. Closing a handle empties the slot and bumps its generation.

So closing an object while another thread is using it is safe:

- a call already running keeps its own `Arc` and finishes normally;
- a later call with the closed handle finds an empty slot or a new generation, and throws `IllegalStateException`;
- a reused slot never answers to an old handle.

`LifecycleTest.closeRacesWithReads` exercises this. With raw pointers, as OpenDAL uses, the same race is a
use-after-free that crashes the JVM.

Objects that were never closed are released by a `java.lang.ref.Cleaner` when they become unreachable. This is a
backstop; native objects hold connections and caches, so code should close them.

Ownership between objects follows the Rust side: a `Repository` holds its own reference to its storage, and a
`Session` to its repository, so closing a parent does not break its children. The one exception is a session's
`Store`, which closes with the session, since a store has no use without it.

## Runtime and class loaders

One tokio runtime serves the process. It starts on the first call and stops in `JNI_OnUnload`, which the JVM calls
when the class loader that loaded the library is collected; the threads must stop before the library's code is
unmapped.

The JVM refuses to load the same library file into two class loaders. The loader therefore extracts a bundled library
to a new temporary file each time, and deletes it once loaded. Windows does not allow deleting a loaded library, so
there the next extraction removes copies that are no longer in use.

## Configuration as JSON

Storage options, credentials and repository options travel to the native side as JSON strings. The Java builders
(`S3Options`, `S3Credentials`, `RepositoryOptions`, ...) produce them, and `native/src/spec.rs` parses them.

The JSON format is the binding's own, not icechunk's serde format. icechunk's serialized types follow its persistence
needs, use several tagging styles, and can change between releases; the binding's format changes only when the
binding does. Two tests pin the format from both sides: `JsonContractTest` checks the exact strings the builders
produce, and the tests in `spec.rs` parse the same strings. `deny_unknown_fields` turns a mismatch into an error
instead of a silently ignored option.

The exception is repository configuration, which is passed through unchanged as icechunk's own `RepositoryConfig`
JSON. icechunk stores that document in every repository, so its format is already stable across releases.

The Java side writes JSON with a small `Json` class instead of a JSON library, so `icechunk-java` has no runtime
dependencies to conflict with an application's.

## Bytes

Chunks can be large, so how values cross the boundary matters for both speed and memory. The numbers below come from
`benchmarks/` (see [Benchmarks](#benchmarks)).

**Reads** come in three forms:

- **`get` copies into a new `byte[]`.** icechunk's buffer is freed as soon as the call returns, so the two copies
  coexist only during the memcpy, and the array is ordinary heap garbage. Streaming 1 GiB of 4 MiB chunks this way
  peaks at about 100 MiB above baseline. This is the default, and what `IcechunkZarrStore` uses.
- **`getInto` copies into a buffer the caller owns.** It allocates nothing, so a caller that reuses one buffer streams
  any amount of data in constant memory.
- **`getBuffer` lends icechunk's buffer itself,** as a read-only direct `ByteBuffer` released by a `Cleaner` when it is
  collected. Slices, duplicates and the read-only view all keep the original reachable, so the memory cannot be
  released while any view exists.

Lending saves the copy, but it ties native memory to garbage collection, and the collector cannot see that memory.
Since a lending read allocates almost nothing on the heap, collections stop happening: streaming the same 1 GiB with
`getBuffer` and only the JDK's default rule (collect when lent memory passes the maximum heap) peaked at 1 GiB above
baseline. `NativeBuffers` therefore counts the bytes lent out and requests a collection past
`icechunk.buffers.limitBytes`, 64 MiB by default. That bounds memory, but each request is a full collection, which
is cheap on a small heap and can take seconds on a large one with much live data. `getBuffer` is for data the caller
keeps anyway, not for streaming.

zarr-java's compression codecs copy their input into a `byte[]` (`Utils.toArray`) whatever the store returns, which
is why the adapter uses `get`. For a 1 MiB value from in-memory storage, `get` and `getBuffer` take the same time, so
icechunk copies internally and the binding's copy is not the bottleneck.

**Writes.** A `byte[]` or heap buffer is copied once into Rust: the JVM may move heap arrays, so Rust cannot keep a
pointer to them. A direct `ByteBuffer` larger than 64 KiB is read in place: the native side wraps the buffer's memory
as a `Bytes` that holds a global reference to the buffer. icechunk drops materialized chunks once they are written,
but in-memory storage keeps the bytes it was given, and small values stay in the session's change set until commit.
So the caller must not modify a buffer after passing it to `set`. Values of 64 KiB or less are always copied. Writes
peak at a few MiB above baseline either way, because each chunk is released once written.

`Store.getMany` batches reads into one native call, which icechunk runs concurrently. Use it when reading many chunks
from object storage; one `get` per chunk pays the network latency each time.

## zarr-java adapter

`IcechunkZarrStore` implements zarr-java's `Store` and `ListableStore`. Most of it is direct delegation. Two details
follow zarr-java's conventions:

- In `get(keys, start, end)`, `end` is exclusive and a negative value means "to the end". A negative `start` means "the
  last `-start` bytes"; the sharding codec uses this to read the shard index.
- `getSize` returns -1 for a missing key. icechunk reports a missing chunk of an existing array as size 0, so
  `Store.size` checks existence first.

zarr-java's own abstract store tests (`StoreTest`, `WritableStoreTest`) write arbitrary keys, which an icechunk store
does not accept, so the adapter has its own tests instead.

## Packaging

`.github/workflows/release.yml` builds the native library on each platform, bundles all of them into the
`icechunk-java` jar under `io/earthmover/icechunk/native/<os>-<arch>/`, runs the tests against that jar, and attaches
the jars to a draft GitHub release. A single jar with every platform suits Fiji update sites and scripting tools that
do not resolve Maven classifiers; per-platform classifier jars can be added for applications that care about size.

- Linux libraries are built in manylinux_2_28 containers, as icechunk-python's wheels are, so they load on older
  distributions.
- Platforms: Linux x86_64 and aarch64, macOS x86_64 and arm64, Windows x86_64.
- Publishing to Maven Central needs a verified groupId namespace and signed artifacts, and is not set up.

To check before relying on it: macOS applications packaged with jpackage and hardened runtime may refuse an unsigned
dylib extracted to a temporary directory.

## Extensions

A library such as an Arraylake client can add native operations without forking this one. It depends on the
`icechunk-jni` crate as an `rlib`, adds its own `Java_...` exports using `icechunk_jni::ext` (`run`, `block_on`, and
`insert_*` to register objects, including its own types), and builds one combined library. Its jar ships that library under `io/earthmover/icechunk/native-ext/<os>-<arch>/`, which the loader prefers.
Handles its native code registers live in the same table as the core's, and `NativeExtensions` wraps them as ordinary
`Repository` and `Storage` objects.

One library, rather than two, is required: a second library would have its own handle table and runtime, and its
handles would mean nothing to the core.

## Dependency on icechunk

`native/Cargo.toml` pins icechunk to a git revision, and `native/Cargo.lock` is committed, so builds are reproducible.
`scripts/fetch-icechunk-fixtures.sh` reads the same revision to fetch matching test fixtures. An extension that pulls
in icechunk from crates.io, as the `arraylake` crate does, needs both to resolve to one copy of icechunk; a
crates.io version pin here would make that simpler than the git pin.

## Testing

- **Rust unit tests** cover the handle table, byte ranges and the JSON specs.
- **Java tests** cover each class, close races, commit conflicts, and the lifetime of lent buffers.
- **zarr-java round trips** write arrays with bytes, zstd, gzip, blosc and sharding codecs, commit, and read them back
  in a new session.
- **icechunk's compatibility repositories.** `CompatibilityFixturesTest` reads the repositories icechunk keeps for
  `test_can_read_old.py` (format v1, v2, and v1 migrated to v2) and checks the same branches, tags, histories,
  listings and values that test checks. It skips what needs a diff API or the MinIO server icechunk's test uses.
- **icechunk-python interop.** `PythonInteropTest` has icechunk-python write a repository, commits on top of it from
  Java, and has icechunk-python check the result.

The last two need fixtures and `uv`. They skip when those are missing, unless `-Dicechunk.tests.strict=true`, which CI
sets.

## Benchmarks

`pixi run bench` runs the JMH benchmarks in `benchmarks/` against a release build of the native library, with JMH's
allocation profiler. `MemoryProbe` in the same module streams a fixed volume of chunks through each read and write
path in a fresh JVM and reports peak resident memory, which JMH cannot see. The full grid takes about half an hour,
so it belongs on a dedicated machine; numbers from a laptop in use are only a rough guide.

Contention between threads comes mostly from icechunk itself: every `Store` call takes the session's tokio `RwLock`,
and `set` takes it three times, once for writing. With 8 threads writing small values to one session, each `set`
waits on the others.

## Open questions

- **Refreshable credentials.** icechunk can call back for fresh credentials. A Java callback would run on a runtime
  thread and may block on HTTP, so it should run on tokio's blocking pool or a Java executor, never on a worker.
- **Timeouts and cancellation.** Blocking calls cannot be interrupted. A per-call or per-session timeout, applied with
  `tokio::time::timeout` inside `block_on`, would bound them.
- **Async API.** `CompletableFuture` variants would spawn the operation on the runtime and complete the future from a
  runtime thread, which then must be attached to the JVM. Completions should be handed to a Java executor so user
  callbacks never run on runtime threads.
- **Logging.** icechunk logs through `tracing`. Forwarding to SLF4J would need a subscriber that hands events to Java
  from runtime threads.
- **Proxies and trust stores.** The native HTTP client ignores `https.proxyHost` and the JVM's `cacerts`. These could be
  read from system properties and passed to the client.
- **Missing operations.** Diff, garbage collection, expiration, rebase, node moves, virtual reference writes, and typed
  configuration builders.
