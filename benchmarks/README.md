# benchmarks

**[JMH](https://github.com/openjdk/jmh) benchmarks of the `Store` API in [icechunk-java](../icechunk-java), and a
probe of the process's peak memory while it streams chunks.**

- **What a native call costs**, so a change to a hot path can be compared before and after.
- **For contributors** changing how calls or bytes cross between Java and Rust. Not published.
- **Java 21**, unlike the published modules, which target Java 8.

## Running

From the repository root:

```sh
pixi run bench                                              # the full grid, about half an hour
pixi run bench "StoreBenchmark.get$ -p chunkBytes=64"       # one benchmark, one parameter value
scripts/bench-coiled.sh [vm-type]                           # the full grid and memory probe on a Coiled VM
```

`pixi run bench` builds a release native library and `benchmarks/target/benchmarks.jar`, then runs JMH with its
allocation profiler and writes `benchmarks/target/results.json`. Arguments are passed to JMH. A laptop in use gives
only rough numbers, so `scripts/bench-coiled.sh` runs the same jar on a dedicated [Coiled](https://coiled.io) VM,
`c7i.4xlarge` by default.

The memory probe runs one mode per JVM, with mode `get`, `set` or `setDirect`:

```sh
java -Dicechunk.native.dir=native/target/release -cp benchmarks/target/benchmarks.jar \
    io.earthmover.icechunk.benchmarks.MemoryProbe get [chunkMiB] [totalMiB]
```

## Contents

| Class | What it measures |
|---|---|
| `StoreBenchmark` | Average time per call of `get`, `get` of a suffix range, `exists`, `getPartialValues`, `set` from a heap array and `set` from a direct buffer, plus the fixed cost of one native call. Parameters: in-memory or local storage, and chunks of 64 B, 64 KiB or 1 MiB. |
| `MemoryProbe` | Peak resident memory of the whole process, including native memory that JMH's allocation profiler cannot see. |
| `Fixtures` | The one-array repository both use. |

## More

- [DESIGN.md](../dev/DESIGN.md#bytes) has the measurements behind the `Store` API's handling of bytes.
- [DESIGN.md](../dev/DESIGN.md#benchmarks) describes thread contention in the benchmarks.
