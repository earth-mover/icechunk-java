# Use in Fiji

Fiji opens icechunk repositories by URL once six jars, built from source, are in its `jars/` folder. Its importer
(**File > Import > HDF5/N5/Zarr/OME-NGFF ...**) and BigDataViewer's N5 viewer
(**Plugins > BigDataViewer > HDF5/N5/Zarr/OME-NGFF Viewer**) then open a URL such as
`s3://icechunk-public-data/v1/era5_weatherbench2|icechunk://branch.main/1x721x1440`. To use icechunk from your own
N5 code instead, see [N5](n5.md).

!!! warning "Experimental"
    These are unreleased builds. Two of the jars replace jars Fiji ships; keep the originals so you can switch back.

## What you need

- [pixi](https://pixi.sh), which provides JDK 21 and Maven for every build on this page.
- [rustup](https://rustup.rs). The native library builds with the Rust toolchain pinned in icechunk-java's
  `rust-toolchain.toml`, which rustup installs on first use.
- git.
- [Fiji](https://fiji.sc).

The first build downloads the Rust and Maven dependencies and compiles the native library, which takes several
minutes.

## Build the jars

Fiji needs six jars: four from icechunk-java, and one each from forks of n5-universe and n5-ij. The forks carry
changes no release contains yet; [Changes in other projects](upstream.md) lists them.

1. Clone the two forks next to your clone of icechunk-java:

    ```sh
    git clone -b kva-provider https://github.com/ianhi/n5-universe.git
    git clone -b icechunk-pipe-uri https://github.com/ianhi/n5-ij.git
    cd icechunk-java
    ```

    The remaining commands run in `icechunk-java`, so that pixi provides Maven.

2. Install the n5-universe fork into your local Maven repository. `icechunk-n5-universe` compiles against it, so
   this step comes before step 4.

    ```sh
    pixi run mvn -B -f ../n5-universe/pom.xml install -DskipTests
    ```

    This builds `../n5-universe/target/n5-universe-3.1.1-SNAPSHOT.jar`.

3. Build the n5-ij fork:

    ```sh
    pixi run mvn -B -f ../n5-ij/pom.xml package -DskipTests
    ```

    This builds `../n5-ij/target/n5-ij-5.0.0.jar`.

4. Build the icechunk-java jars:

    ```sh
    pixi run fiji-jars
    ```

    The `fiji-jars` task builds the native library in release mode, bundles it into the icechunk-java jar for the
    platform you build on, installs the four modules, and copies their jars into `target/fiji/`.

The icechunk-java jar holds the native library for one platform only, the one it was built on. A jar for another
operating system or processor has to be built there.

## Install into Fiji

Quit Fiji first; it reads `jars/` only at startup. Then move Fiji's own n5-universe and n5-ij jars aside and copy in
the six jars. Set `FIJI` to the folder that contains Fiji's `jars/` folder:

```sh
FIJI=/Applications/Fiji
mkdir -p ../fiji-original-jars
mv "$FIJI"/jars/n5-universe-*.jar "$FIJI"/jars/n5-ij-*.jar ../fiji-original-jars/
cp target/fiji/*.jar \
   ../n5-universe/target/n5-universe-3.1.1-SNAPSHOT.jar \
   ../n5-ij/target/n5-ij-5.0.0.jar \
   "$FIJI"/jars/
```

When you rebuild later, copy the jars again; the file names stay the same, so they replace the earlier copies.

To switch back, delete the six jars from `jars/` and move the originals from `fiji-original-jars/` back.

| Jar | What it does |
|---|---|
| `icechunk-java` | The icechunk API for Java, with the native library that reads and writes repositories. |
| `icechunk-n5` | `IcechunkKeyValueAccess`, through which N5 and n5-zarr read a repository. |
| `icechunk-n5-universe` | Lets n5-universe's `N5Factory` open icechunk URLs. Fiji's importer and viewer open locations through `N5Factory`. |
| `icechunk-n5-codecs` | The `numcodecs.pcodec` and `numcodecs.zlib` codecs, which n5-zarr lacks. The ERA5 repository below uses pcodec. |
| `n5-universe-3.1.1-SNAPSHOT` | Replaces Fiji's n5-universe. Adds the `KeyValueAccessProvider` service that `icechunk-n5-universe` implements. |
| `n5-ij-5.0.0` | Replaces Fiji's n5-ij. Its dataset dialog accepts URLs containing `\|`, stays responsive on repositories with thousands of nodes, and opens OME-Zarr images with their channel colours. |

To open Arraylake repositories as well, add the jar of the separate icechunk-arraylake-java project.

## Open a repository

Start Fiji, then:

1. Choose **File > Import > HDF5/N5/Zarr/OME-NGFF ...**.
2. Paste this URL into the dataset field. It opens a public repository of ERA5 weather data, which needs no
   credentials:

    ```text
    s3://icechunk-public-data/v1/era5_weatherbench2|icechunk://branch.main/1x721x1440
    ```

3. Click **Detect datasets**. The tree lists the repository's arrays.
4. Select `2m_temperature`, tick **Open as virtual**, and click **OK**. Without **Open as virtual**, Fiji tries to read the
   whole array, about 2.3 TB, into memory.

The image is air temperature 2 m above the ground, in kelvin. Each slice is one hour, 1440 by 721 pixels, and the
stack runs through time.

BigDataViewer opens the same URLs: choose **Plugins > BigDataViewer > HDF5/N5/Zarr/OME-NGFF Viewer** and use the same
dialog. It suits multiscale OME-Zarr images, since it reads only the chunks on screen.

[Open from a URL](n5.md#open-from-a-url) covers the URL syntax, other storage locations, and how credentials are
found.

[Example: segment nuclei with StarDist](stardist.md) opens a microscopy image from Arraylake and segments it with
StarDist through Appose.

## Save into a repository

**File > Save As > icechunk...** saves the active image into a repository as OME-Zarr and commits it, on a branch you
choose. [Example: save results into icechunk](write-back.md) walks through saving an image and two segmentations of
it, and opening an earlier one again by its snapshot ID.

## Troubleshooting

**The dialog reads the URL as a local file path.** Fiji is still running its own n5-ij, or it was not restarted.
Check that `jars/` holds exactly one `n5-ij-*.jar` and one `n5-universe-*.jar`, the ones built above, and restart
Fiji.

**`UnsatisfiedLinkError: the icechunk native library has interface version N but this jar needs version M`.** The
icechunk-java jar and the native library that loaded come from different builds. Look in `jars/` for a second
`icechunk-java-*.jar` from an earlier build, and remove it. If you added the icechunk-arraylake-java jar, it carries
its own build of the native library, which loads in place of the one in the icechunk-java jar; rebuild it against the
same icechunk-java sources.
