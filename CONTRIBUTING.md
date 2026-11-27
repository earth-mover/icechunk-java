# Contributing

Read [DESIGN.md](dev/DESIGN.md) first; it explains the call flow and handle rules that every change has to follow.

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
| `native/src/spec.rs` | The JSON formats the Java classes send. |
| `native/src/results.rs` | The JSON formats of record results the Java classes read. |
| `native/src/{storage,repository,session,store,logging}.rs` | Native methods, one module per Java class. |
| `native/src/codecs.rs` | The pcodec decoder behind `Pcodec`, used by `icechunk-n5-codecs`. |
| `native/src/error.rs` | The native error type, and how icechunk's errors map onto Java exceptions. |
| `icechunk-java/` | The public Java API and its internals (`Native`, `NativeHandle`, `NativeLoader`, `Json`, `JsonReader`). |
| `benchmarks/` | JMH benchmarks of the Store API. |
| `test-support/` | `TestEnvironment`, which the modules' tests use to find fixtures and run Python. Not published. |
| `property-tests/` | Stateful property tests, run with [hegel](https://github.com/hegeldev/hegel-java), which compare random sequences of operations with a model. Built for Java 21, so CI skips them on Java 8. Set `HEGEL_TEST_CASES` for a longer run; a failure prints the shrunk steps and a `reproduceFailure` string that replays them. |
| `icechunk-zarr-java/` | The zarr-java adapter, and the zarr-java, fixture and Python interop tests. |
| `icechunk-n5/` | The N5 adapter, and its n5-zarr and Python interop tests. |
| `icechunk-n5-universe/` | Opens icechunk URLs through n5-universe's `N5Factory`. Builds only with `-Pn5-universe-provider`. |
| `icechunk-n5-codecs/` | numcodecs Zarr v3 codecs (pcodec, zlib) for n5-zarr. |
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
   `HandleCleaner.reachabilityFence(this)` in the `finally` block.
4. If it takes a new kind of option, add it to the Java builder and to `spec.rs`, and extend both `JsonContractTest`
   and the `spec.rs` tests with the same JSON string.
5. If it returns a record, add a borrowing struct to `results.rs` and a reader on the Java class using `JsonReader`'s
   field accessors, and extend both the `results.rs` tests and `ResultsContractTest` with the same JSON string. Return
   scalars and lists of strings directly.
6. Constants shared by both sides (`Native.RANGE_*`, `Native.LIST_*` and so on) are defined twice. Change both;
   `contract_tests.rs` fails if they disagree, and also needs a line for any new constant.
7. If the change alters a native method's name or signature, a shared constant, or a JSON format, raise
   `Native.ABI_VERSION` and `ABI_VERSION` in `native/src/lib.rs` together. Loading checks them, so a jar never runs
   against a library built from other sources; a mismatched JNI signature would otherwise crash the JVM.
8. If it is on a hot path, add a case to `benchmarks/` and compare before and after with `pixi run bench`.

## Updating icechunk

Change the icechunk version in `native/Cargo.toml`, run `cargo update -p icechunk --manifest-path native/Cargo.toml`,
and run `pixi run test-strict`. The fixture script reads the new version from `Cargo.lock` and fetches the fixtures
from its release tag. To test against an unreleased icechunk, point the dependency at a git revision instead; the
script handles that too.

`KnownIcechunkBugsTest` asserts the behaviour of icechunk bugs that the tests work around. If one of its tests fails
after an update, that bug is fixed: delete the test and remove the workaround it names.

## Releasing

Pushing a `v*` tag starts `.github/workflows/release.yml`, which does the rest:

1. `git tag v0.1.0 && git push origin v0.1.0`. The workflow builds the native library on every platform, bundles them
   into the jar, and runs the tests on each platform against it.
2. Approve the `publish` job in the Actions tab. It waits for a reviewer of the `maven-central` environment, then signs
   the jars and uploads them to the Central Portal.
3. On [central.sonatype.com](https://central.sonatype.com), under Publish, check the deployment and press
   **Publish**. The jars appear on Maven Central, usually within the hour. A published version can never be changed or
   removed; press **Drop** instead to discard it.
4. Publish the draft GitHub release the workflow created, with the jars attached.

Don't create the release with GitHub's "Draft a new release" button. The tag it creates starts the workflow, whose last
step then fails because the release already exists.

To try the pipeline without publishing, push a tag such as `v0.1.0-rc1`, drop the deployment in the portal, and delete
the draft release and the tag. The workflow can also be run by hand from `main` with a `version` input, which uploads
without creating a GitHub release.

### Publishing credentials

The `maven-central` environment holds four secrets, and only `main` and `v*` tags can use them:

- `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`: a Central Portal user token (View Account > Generate User
  Token). Tokens belong to one person's account and expire. When the upload step fails with 401, generate a new token
  and replace both secrets. Revoke the old one if someone with access to it leaves.
- `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`: the armored secret key and passphrase of the "icechunk-java release signing"
  key, fingerprint `CCA9E4D916273A33D8402FB172C833F9E8DDCA94`, published on keyserver.ubuntu.com, where Central checks
  signatures. It expires on 2029-11-26. Before then, extend it with `gpg --quick-set-expire` and send it to the
  keyserver again, or make a new key, publish it, and replace both secrets.

The `io.icechunk` namespace was verified with a DNS TXT record on icechunk.io, whose DNS is managed at Namecheap. The
record can be removed once the namespace is verified. The namespace covers every groupId under `io.icechunk`, so other
projects can publish there with their own environment and secrets.

## Commits

Describe what the change does and why. Keep unrelated changes in separate commits.
