# native

The Rust crate `icechunk-jni`, which builds `icechunk_jni`, the native library behind the Java bindings. It
implements the native methods declared in `Native.java` in [icechunk-java](../icechunk-java) on top of the
[icechunk](https://crates.io/crates/icechunk) Rust crate. Every Java call into icechunk goes through it.

You only work here as a contributor: to add or change a native method, update icechunk, or debug the JNI layer.
Users of the jars get a prebuilt copy, bundled in release jars of `icechunk-java`.

## Building and checking

From the repository root, with [pixi](https://pixi.sh) and [rustup](https://rustup.rs) installed. The Rust toolchain
pinned in `rust-toolchain.toml` installs on first use.

| Command | What it does |
|---|---|
| `pixi run build-native` | Debug build into `native/target/debug`, where the Maven build and tests load it from. |
| `pixi run build-native-release` | Release build into `native/target/release`, used by the benchmarks. |
| `pixi run test-native` | Rust unit tests. |
| `pixi run lint-native` | `cargo fmt --check` and clippy with warnings as errors. |
| `pixi run format` | Formats the Rust and Java code. |

Clippy denies `unwrap`, `expect` and `panic` outside tests: a panic in native code becomes an error the Java caller
has to handle.

## Contents

| Path | Contents |
|---|---|
| `src/lib.rs` | Module list, `ABI_VERSION`, and `ext`, the functions an extension crate uses to add its own native methods. |
| `src/call.rs` | Running a native method: `run`, `block_on`, and errors as Java exceptions. |
| `src/error.rs` | The error type and the kinds of Java exception it maps to. |
| `src/handles.rs` | The handle table that maps the `long` a Java object holds to an icechunk object. |
| `src/runtime.rs` | The process-wide tokio runtime. |
| `src/buffers.rs` | Reading Java's direct buffers in place on write. |
| `src/spec.rs`, `src/results.rs` | The JSON formats exchanged with the Java classes for options and results. |
| `src/{storage,repository,session,store,logging}.rs` | Native methods, one module per Java class. |
| `src/codecs.rs` | The pcodec decoder used by [icechunk-n5-codecs](../icechunk-n5-codecs). |
| `src/contract_tests.rs` | Checks that constants and error kinds shared with the Java side agree. |

The crate builds as both a `cdylib`, the library the jar loads, and an `rlib`, so an extension crate can link it into
one combined library. `Cargo.toml` pins an exact icechunk release.

## More

- [CONTRIBUTING.md](../CONTRIBUTING.md#adding-a-native-method) lists the steps to add a native method and to update
  icechunk.
- [DESIGN.md](../dev/DESIGN.md) explains the call flow, handles, runtime and extensions.
