# tests

The Python side of the interop tests, which check that icechunk-java and icechunk-python read each other's
repositories. The Java tests live in each module's `src/test`; this directory holds only the Python scripts they run.

You only need it as a contributor, when an interop test fails or when you add one.

## Running

The scripts run as part of the normal test build, from the repository root:

```sh
pixi run test         # skips the interop tests if uv is missing
pixi run test-strict  # fails instead of skipping, as CI does
```

The Java tests start each script with [uv](https://docs.astral.sh/uv/), using the latest icechunk, zarr and numpy
releases (`TestEnvironment.python` in `icechunk-java/src/test`). Install uv to run them.

## Contents

| Script | Run by | What it does |
|---|---|---|
| `python/write_repo.py` | `PythonInteropTest` | icechunk-python writes a repository for Java to read and commit on top of. |
| `python/check_repo.py` | `PythonInteropTest` | icechunk-python checks the commit Java made. |
| `python/check_n5_repo.py` | `PythonReadsN5Test` | zarr-python reads the plain and sharded datasets Java wrote through n5-zarr and `IcechunkKeyValueAccess`. |
| `python/write_codec_arrays.py` | `NumcodecsCodecsTest` | zarr-python writes arrays with the `numcodecs.pcodec` and `numcodecs.zlib` codecs for n5-zarr to read. |

Each script takes the path of a repository or directory as its only argument. The `check_` scripts fail with an
assertion error when a check does not hold.

## More

- [CONTRIBUTING.md](../CONTRIBUTING.md) lists every build and test command.
- [DESIGN.md](../dev/DESIGN.md#testing) describes all the test suites, including icechunk's compatibility repositories.
