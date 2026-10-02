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
| `pixi run bench [jmh args]` | Build a release native library and run the JMH benchmarks locally, with allocation per operation. Narrow the grid for local runs, for example `pixi run bench "StoreBenchmark.get$ -p chunkBytes=64"`; the full grid takes about half an hour. |
| `scripts/bench-coiled.sh [vm-type]` | Run the full benchmark grid and memory probe on a [Coiled](https://coiled.io) VM. |

The Java build runs [Error Prone](https://errorprone.info) and `javac -Xlint:all -Werror`, and checks formatting with
Spotless (palantir-java-format), so warnings and formatting fail the build. Clippy denies `unwrap`, `expect` and
`panic` outside tests: a panic in native code becomes an error the Java caller has to handle.

## Layout

| Path | Contents |
|---|---|
| `native/src/call.rs` | Running a native method: `run`, `block_on`, and errors as Java exceptions. |
| `native/src/buffers.rs` | Reading Java's direct buffers in place on write. |
| `native/src/contract_tests.rs` | Checks that constants and error kinds shared with the Java side agree. |
| `native/src/handles.rs` | The handle table. |
| `native/src/runtime.rs` | The process-wide tokio runtime. |
| `native/src/spec.rs` | The JSON formats the Java builders send. |
| `native/src/{storage,repository,session,store}.rs` | Native methods, one module per Java class. |
| `icechunk-java/` | The public Java API and its internals (`Native`, `NativeHandle`, `NativeLoader`, `Json`). |
| `benchmarks/` | JMH benchmarks of the Store API. |
| `icechunk-zarr-java/` | The zarr-java adapter, and the zarr-java, fixture and Python interop tests. |
| `examples/` | Runnable examples. |
| `tests/python/` | The icechunk-python side of the interop test. |
| `scripts/fetch-icechunk-fixtures.sh` | Fetches icechunk's compatibility repositories at the pinned revision. |

## Adding a native method

1. Declare it in `Native.java`, taking the handle as a `long` and returning the result directly:
   `static native String[] thingList(long handle, String prefix)`.
2. Implement it in the matching Rust module with the `native!` macro, which writes the JNI export:
   `native! { fn thingList(env, handle: jlong, prefix: JString<'l>) -> JObjectArray<'l, JString<'l>> { ... } }`.
   In the body, look up the handle, drive the icechunk future with `block_on`, and convert the result. Return errors
   with `?`; they are thrown as Java exceptions.
3. Call it from the public class as `Native.thingList(handle(), prefix)` inside `try`, with
   `Reference.reachabilityFence(this)` in the `finally` block.
4. If it takes a new kind of option, add it to the Java builder and to `spec.rs`, and extend both `JsonContractTest`
   and the `spec.rs` tests with the same JSON string.
5. Constants shared by both sides (`Native.RANGE_*`, `Native.VERSION_*` and so on) are defined twice. Change both;
   `contract_tests.rs` fails if they disagree, and also needs a line for any new constant.
6. If it is on a hot path, add a case to `benchmarks/` and compare before and after with `pixi run bench`.

## Updating icechunk

Change the icechunk version in `native/Cargo.toml`, run `cargo update -p icechunk --manifest-path native/Cargo.toml`,
and run `pixi run test-strict`. The fixture script reads the new version from `Cargo.lock` and fetches the fixtures
from its release tag. To test against an unreleased icechunk, point the dependency at a git revision instead; the
script handles that too.

## Commits

Describe what the change does and why. Keep unrelated changes in separate commits.
