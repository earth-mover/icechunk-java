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
Java internals   NativeCall (one per call)   NativeHandle (owns a long)
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

Every operation, even a cheap one, runs the same way:

1. The Java method creates a `NativeCall` and passes it, with the arguments, to a native method.
2. The native method reads the arguments on the Java thread, spawns a task on the tokio runtime, and returns a task id
   at once.
3. The Java thread waits on the `NativeCall`.
4. When the task finishes, a runtime thread calls one `on*` method on the `NativeCall` (`onBytes`, `onString`,
   `onError` and so on), which stores the result and wakes the waiting thread.
5. The waiting thread returns the result, or builds and throws the exception.

Running everything as a task keeps a single code path, and has these consequences:

- **No native method blocks.** Java threads never call tokio's `block_on`, which would panic if the caller were itself
  a runtime thread.
- **Exceptions have useful stack traces.** The Java exception is created on the waiting thread, so it shows the caller.
  Native code never constructs Java exception classes, which also means it never has to find a class from a runtime
  thread whose class loader might not see it.
- **Calls can be interrupted.** If the waiting thread is interrupted, it cancels the task through `Native.cancel` and
  throws. The task is aborted at its next await point. Interrupting a commit can leave it committed or not, as with
  any client that disconnects mid-request.
- **A panic does not crash the JVM.** Each task is wrapped in `catch_unwind`, and a panic is reported as an error.
  Panics on the Java thread are caught by jni-rs and thrown as `RuntimeException`.

A call made from a runtime thread is rejected with `IllegalStateException`. Waiting there would block a thread the
runtime needs to produce the result. No user code runs on runtime threads today; the check is there for when Java
callbacks arrive (see [Open questions](#open-questions)).

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

All handles and tasks share one tokio runtime. Each holds an `Arc` to it; a static holds only a `Weak`. When the last
handle closes and the last task finishes, the runtime shuts down and its threads exit. The next call starts a new one.

This matters for applications that load the library more than once, such as plugin hosts (Fiji) or application
servers. Runtime threads are attached to the JVM, and each has the binding's class loader as its context class loader.
A runtime that lived forever would keep that class loader, and everything it loaded, alive after the plugin unloads.
OpenDAL's binding uses the same refcounted runtime ([executor.rs](https://github.com/apache/opendal/blob/main/bindings/java/src/executor.rs)).

The JVM refuses to load the same library file into two class loaders. The loader therefore extracts a bundled
library to a new temporary file each time.

Runtime threads detach from the JVM explicitly when they stop. Relying on detach at thread exit can deadlock on
Windows ([jni-rs#701](https://github.com/jni-rs/jni-rs/issues/701)).

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

Values are copied between Java arrays and Rust buffers: once from `byte[]` into Rust on `set`, once from Rust into a
new `byte[]` on `get`. Zero-copy is possible in principle, through direct `ByteBuffer`s over Rust memory, but it makes
buffer lifetime part of the API, and the main consumers copy anyway. zarr-java wraps results in heap buffers, and N5's
`ReadData.from(ByteBuffer)` rejects direct buffers.

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

Not built yet. The plan:

- One jar per module, with the native library for every platform inside `icechunk-java`, under
  `io/earthmover/icechunk/native/<os>-<arch>/`. A single jar suits Fiji update sites and scripting tools that do not
  resolve Maven classifiers. Per-platform classifier jars can be added for applications that care about size.
- Linux libraries built in manylinux containers, as icechunk-python's wheels are, so they load on older distributions.
- Platforms: Linux x86_64 and aarch64, macOS x86_64 and arm64, Windows x86_64.

To check before shipping: macOS applications packaged with jpackage and hardened runtime may refuse an unsigned
dylib extracted to a temporary directory.

## Extensions

A library such as an Arraylake client can add native operations without forking this one. It depends on the
`icechunk-jni` crate as an `rlib`, adds its own `Java_...` exports using `icechunk_jni::ext`, and builds one combined
library. Its jar ships that library under `io/earthmover/icechunk/native-ext/<os>-<arch>/`, which the loader prefers.
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
- **Java tests** cover each class, close races, interruption, and commit conflicts.
- **zarr-java round trips** write arrays with bytes, zstd, gzip, blosc and sharding codecs, commit, and read them back
  in a new session.
- **icechunk's compatibility repositories.** `CompatibilityFixturesTest` reads the repositories icechunk keeps for
  `test_can_read_old.py` (format v1, v2, and v1 migrated to v2) and checks the same branches, tags, histories,
  listings and values that test checks. It skips what needs a diff API or the MinIO server icechunk's test uses.
- **icechunk-python interop.** `PythonInteropTest` has icechunk-python write a repository, commits on top of it from
  Java, and has icechunk-python check the result.

The last two need fixtures and `uv`. They skip when those are missing, unless `-Dicechunk.tests.strict=true`, which CI
sets.

## Open questions

- **Refreshable credentials.** icechunk can call back for fresh credentials. A Java callback would run on a runtime
  thread and may block on HTTP, so it should run on tokio's blocking pool or a Java executor, never on a worker.
- **Async API.** `CompletableFuture` variants are a small step from the current design, since every call is already a
  task. Completions must be delivered on a Java executor so that user callbacks do not run on runtime threads.
- **Logging.** icechunk logs through `tracing`. Forwarding to SLF4J would need a subscriber that hands events to Java
  from runtime threads.
- **Proxies and trust stores.** The native HTTP client ignores `https.proxyHost` and the JVM's `cacerts`. These could be
  read from system properties and passed to the client.
- **Missing operations.** Diff, garbage collection, expiration, rebase, node moves, virtual reference writes, and typed
  configuration builders.
