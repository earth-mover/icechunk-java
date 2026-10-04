# tests

The Python side of the interop tests, which check that icechunk-java and icechunk-python read each other's
repositories.

- This directory holds only the Python scripts. The Java tests live in each module's `src/test` and start these scripts.
- Read it when an interop test fails or when you add one.
- The scripts need [uv](https://docs.astral.sh/uv/), which runs each script with the latest icechunk, zarr and numpy releases.

## Running

The scripts run as part of the normal test build, from the repository root:

```sh
pixi run test         # skips the interop tests if uv is missing
pixi run test-strict  # fails instead of skipping, as CI does
```

`TestEnvironment.python` in `icechunk-java/src/test` starts each script with uv.

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
