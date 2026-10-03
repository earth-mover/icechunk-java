# Changes in other projects

Opening icechunk repositories by URL, and opening them in Fiji, depends on changes to two Saalfeld lab projects,
[n5-universe](https://github.com/saalfeldlab/n5-universe) and [n5-ij](https://github.com/saalfeldlab/n5-ij), that no
release contains yet. They live on branches of forks until they are proposed and merged upstream:

- n5-universe: [`ianhi/n5-universe`, branch `kva-provider`](https://github.com/ianhi/n5-universe/tree/kva-provider),
  based on n5-universe `main` (3.1.1-SNAPSHOT).
- n5-ij: [`ianhi/n5-ij`, branch `icechunk-pipe-uri`](https://github.com/ianhi/n5-ij/tree/icechunk-pipe-uri), based on
  the n5-ij 5.0.0 release that Fiji ships.

Using icechunk from N5 code needs neither: `IcechunkKeyValueAccess` works with released n5, n5-zarr and n5-universe,
as the [N5](n5.md) page shows.

## What needs which change

| Goal | With released versions | Needs |
|---|---|---|
| Use icechunk from N5 code: `IcechunkKeyValueAccess`, or `N5Factory.openReader(format, kva, uri)` | works | nothing |
| Open an icechunk URL from code, or from any tool that uses `N5Factory` | not possible: n5-universe has no way for another jar to add a backend | n5-universe [`fcc83c0`](https://github.com/ianhi/n5-universe/commit/fcc83c0) |
| Type a URL containing `\|` into Fiji's HDF5/N5/Zarr/OME-NGFF dialog | the dialog reads it as a local path | also n5-ij [`4441e7f`](https://github.com/ianhi/n5-ij/commit/4441e7f) |
| Browse a repository with thousands of nodes in the dialog | the dialog freezes Fiji | n5-ij [`29742b3`](https://github.com/ianhi/n5-ij/commit/29742b3) |
| Open OME-Zarr images with their `omero` channel colours, ranges and labels | images open as grey stacks | n5-ij [`8e180b9`](https://github.com/ianhi/n5-ij/commit/8e180b9) |
| Select an OME-Zarr image, not only its full-resolution array, in the dialog | the selection is cleared | n5-ij [`2fa2799`](https://github.com/ianhi/n5-ij/commit/2fa2799) |
| Keep Fiji responsive while the selected image loads | the window freezes until the read finishes | n5-ij [`48b03b3`](https://github.com/ianhi/n5-ij/commit/48b03b3) |
| Let an application register its own backends at run time, such as Paintera's writable containers | not possible | n5-universe [`2757f10`](https://github.com/ianhi/n5-universe/commit/2757f10) |

Only the first n5-universe commit is needed to open icechunk URLs at all, and `icechunk-n5-universe` builds only
against it (Maven profile `-Pn5-universe-provider`). The first n5-ij commit is needed to type those URLs into Fiji.
The other n5-ij commits fix problems any large OME-Zarr container meets, icechunk or not.

## The changes

**n5-universe**

- [`fcc83c0`](https://github.com/ianhi/n5-universe/commit/fcc83c0): a `KeyValueAccessProvider` service interface.
  `N5Factory` asks providers found through `java.util.ServiceLoader` before its built-in backends, so a jar on the
  classpath can claim URLs such as `s3://bucket/repo|icechunk:@branch.main`. Strings with a scheme are parsed as
  URIs even when they contain characters a URI must escape, instead of being taken for local paths.
- [`2757f10`](https://github.com/ianhi/n5-universe/commit/2757f10): `KeyValueAccessProvider.register` and
  `unregister`, for providers an application adds at run time.

**n5-ij**

- [`4441e7f`](https://github.com/ianhi/n5-ij/commit/4441e7f): the dataset dialog accepts URLs that need escaping,
  such as `s3://bucket/repo|icechunk:@tag.v1`.
- [`29742b3`](https://github.com/ianhi/n5-ij/commit/29742b3): the dialog finds each discovered node by walking its
  path rather than searching the whole tree, sorts the tree once, and matches names such as `1X [1]` that contain
  regular-expression characters.
- [`8e180b9`](https://github.com/ianhi/n5-ij/commit/8e180b9): images with an OME-NGFF `omero` block open as
  composites with its channel colours, display ranges and labels.
- [`2fa2799`](https://github.com/ianhi/n5-ij/commit/2fa2799): selecting an OME-NGFF image group opens its
  full-resolution level.
- [`48b03b3`](https://github.com/ianhi/n5-ij/commit/48b03b3): an image opened from the dialog loads on its own
  thread instead of the Swing event thread. Macros still wait for the image.

## Install the forks

[Use in Fiji](fiji.md) walks through every step: cloning both branches, installing the n5-universe fork into your
local Maven repository, building the n5-ij fork and the icechunk-java jars, and replacing Fiji's own n5-universe and
n5-ij jars. Code that only uses `N5Factory` needs the n5-universe branch alone:

```sh
git clone -b kva-provider https://github.com/ianhi/n5-universe.git
pixi run mvn -B -f n5-universe/pom.xml install -DskipTests
```

Run it from a clone of icechunk-java, where pixi provides Maven and the JDK.
