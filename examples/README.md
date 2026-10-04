# examples

**Small programs that use the icechunk Java bindings.** Read them as sample code, or run them from a clone of this
repository. They are not published.

| Example | What it does |
|---|---|
| [`Quickstart`](src/main/java/io/earthmover/icechunk/examples/Quickstart.java) | Creates a local repository, writes a zarr-java array, commits twice, and reads both versions back. |
| [`ReadPublicData`](src/main/java/io/earthmover/icechunk/examples/ReadPublicData.java) | Reads one time step of ERA5 2 m temperature from a public S3 repository, without credentials, using zarr-java. |
| [`N5Basics`](src/main/java/io/earthmover/icechunk/examples/N5Basics.java) | Writes and reads a dataset with N5's n5-zarr, reads an older snapshot, opens through n5-universe, and keeps one writer across commits. |
| [`fiji/Era5Temperature.groovy`](fiji/Era5Temperature.groovy) | A Fiji script that opens the same ERA5 data as an image stack through N5. |

## Run them

You need [pixi](https://pixi.sh) and [rustup](https://rustup.rs). From the repository root:

```sh
pixi run example                 # Quickstart
pixi run example ReadPublicData
pixi run example N5Basics
```

`pixi run example` builds the native library, installs the modules the examples depend on into your local Maven
repository, and runs the class named by the argument.

The Fiji script needs the `icechunk-java` and `icechunk-n5` jars in Fiji's `jars/` folder. Open it in Fiji's script
editor (**File > New > Script...**, language Groovy) and run it there.

## Edit them

The documentation includes sections of `N5Basics.java` through the `--8<--` markers in its comments. Keep those
markers when you edit it.

## More

- [Java API](../docs/java-api.md) describes the API these programs use.
- [N5](../docs/n5.md) walks through `N5Basics` section by section.
