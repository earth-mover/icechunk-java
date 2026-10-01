# Contributing

Read [DESIGN.md](DESIGN.md) first; it explains the call flow and handle rules that every change has to follow.

## Setup

Install [pixi](https://pixi.sh) and [rustup](https://rustup.rs). pixi provides JDK 21 and Maven; rustup installs the
Rust toolchain pinned in `rust-toolchain.toml` on first use. The interop tests also need
[uv](https://docs.astral.sh/uv/).

## Commands

| Command | What it does |
|---|---|
| `pixi run test` | Build the native library, fetch the icechunk fixtures, build and test everything. |
| `pixi run test-strict` | The same, but fail instead of skip when fixtures or `uv` are missing. CI runs this. |
| `pixi run check` | Rust formatting and clippy, Rust tests, then `test`. Run before committing. |
| `pixi run format` | Format the Rust and Java code. |
| `pixi run example [Name]` | Run an example from `examples/`. Defaults to `Quickstart`. |

The Java build runs [Error Prone](https://errorprone.info) and `javac -Xlint:all -Werror`, and checks formatting with
Spotless (palantir-java-format), so warnings and formatting fail the build. Clippy denies `unwrap`, `expect` and
`panic` outside tests: a panic in native code becomes an error the Java caller has to handle.

## Layout

| Path | Contents |
|---|---|
| `native/src/call.rs` | Starting tasks and reporting results to `NativeCall`. |
| `native/src/handles.rs` | The handle table. |
| `native/src/runtime.rs` | The shared tokio runtime and JVM thread attachment. |
| `native/src/spec.rs` | The JSON formats the Java builders send. |
| `native/src/{storage,repository,session,store}.rs` | Native methods, one module per Java class. |
| `icechunk-java/` | The public Java API and its internals (`Native`, `NativeCall`, `NativeHandle`, `NativeLoader`, `Json`). |
| `icechunk-zarr-java/` | The zarr-java adapter, and the zarr-java, fixture and Python interop tests. |
| `examples/` | Runnable examples. |
| `tests/python/` | The icechunk-python side of the interop test. |
| `scripts/fetch-icechunk-fixtures.sh` | Fetches icechunk's compatibility repositories at the pinned revision. |

## Adding a native method

1. Declare it in `Native.java`: `static native long thingDo(NativeCall call, long handle, ...)`.
2. Implement `Java_io_earthmover_icechunk_Native_thingDo` in the matching Rust module. Copy an existing method: wrap
   the body in `call::entry`, read the Java arguments inside `call::start`'s closure, and return an `async move` block
   that produces a `Reply`.
3. Call it from the public class through the matching `NativeCall.run*` helper. Read `handle()` and copy any fields
   into locals before the lambda.
4. If it takes a new kind of option, add it to the Java builder and to `spec.rs`, and extend both `JsonContractTest`
   and the `spec.rs` tests with the same JSON string.
5. Constants shared by both sides (`Native.RANGE_*`, `Native.VERSION_*` and so on) are defined twice. Change both.

## Updating icechunk

Change the `rev` in `native/Cargo.toml`, run `cargo update -p icechunk --manifest-path native/Cargo.toml`, and run
`pixi run test-strict`. The fixture script reads the new revision automatically.

## Commits

Describe what the change does and why. Keep unrelated changes in separate commits.
