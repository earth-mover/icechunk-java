# Use in Fiji

Fiji opens and saves icechunk repositories once six jars, built from source, are in its `jars/` folder.

- Open a repository by URL in Fiji's importer or BigDataViewer, for example
  `s3://icechunk-public-data/v1/era5_weatherbench2|icechunk://branch.main/1x721x1440`.
- Save the active image as OME-Zarr with **File > Save As > icechunk...**, which commits it to a branch.
- Install by building the jars and copying them into Fiji. Two of them replace jars Fiji ships, so keep the
  originals to switch back.

To use icechunk from your own N5 code instead, see [N5](n5.md).

!!! warning "Experimental"
    These are unreleased builds of icechunk-java and of forks of n5-universe and n5-ij.

## What you need

- [pixi](https://pixi.sh), which provides JDK 21 and Maven.
- [rustup](https://rustup.rs), which installs the Rust toolchain pinned in `rust-toolchain.toml` on first use.
- git.
- [Fiji](https://fiji.sc).

The first build compiles the native library and takes several minutes.

## Build the jars

The six jars are four from icechunk-java and one each from forks of n5-universe and n5-ij.
[Changes in other projects](upstream.md) lists what the forks change.

1. Clone icechunk-java and the two forks side by side. Run the remaining commands in `icechunk-java`, where pixi
   provides Maven.

    ```sh
    git clone https://github.com/earth-mover/icechunk-java.git
    git clone -b kva-provider https://github.com/ianhi/n5-universe.git
    git clone -b icechunk-pipe-uri https://github.com/ianhi/n5-ij.git
    cd icechunk-java
    ```

2. Install the n5-universe fork into your local Maven repository. `icechunk-n5-universe` compiles against it.

    ```sh
    pixi run mvn -B -f ../n5-universe/pom.xml install -DskipTests
    ```

3. Build the n5-ij fork:

    ```sh
    pixi run mvn -B -f ../n5-ij/pom.xml package -DskipTests
    ```

4. Build the four icechunk-java jars into `target/fiji/`:

    ```sh
    pixi run fiji-jars
    ```

The icechunk-java jar bundles the native library for the platform it was built on only. Build on each operating
system and processor you need.

## Install into Fiji

Quit Fiji, since it reads `jars/` only at startup. Then move Fiji's n5-universe and n5-ij jars aside and copy in the
six jars. Set `FIJI` to the folder that contains `jars/`:

```sh
FIJI=/Applications/Fiji
mkdir -p ../fiji-original-jars
mv "$FIJI"/jars/n5-universe-*.jar "$FIJI"/jars/n5-ij-*.jar ../fiji-original-jars/
cp target/fiji/*.jar \
   ../n5-universe/target/n5-universe-3.1.1-SNAPSHOT.jar \
   ../n5-ij/target/n5-ij-5.0.0.jar \
   "$FIJI"/jars/
```

- After a rebuild, copy the jars again. The file names stay the same, so they replace the earlier copies.
- To switch back, delete the six jars from `jars/` and move the originals back from `fiji-original-jars/`.
- To open Arraylake repositories too, add the jar of the separate [icechunk-arraylake-java](https://github.com/earth-mover/icechunk-arraylake-java) project.

| Jar | Role in Fiji |
|---|---|
| `icechunk-java` | The icechunk API and the native library that reads and writes repositories. |
| `icechunk-n5` | Lets N5 and n5-zarr read a repository. |
| `icechunk-n5-universe` | Lets n5-universe's `N5Factory`, which the importer and viewer use, open icechunk URLs. Holds the Save command. |
| `icechunk-n5-codecs` | The pcodec and zlib codecs, which n5-zarr lacks. The ERA5 repository below needs pcodec. |
| `n5-universe-3.1.1-SNAPSHOT` | Replaces Fiji's n5-universe so `icechunk-n5-universe` can plug into it. |
| `n5-ij-5.0.0` | Replaces Fiji's n5-ij so its dataset dialog accepts icechunk URLs. [Changes in other projects](upstream.md) lists its other fixes. |

## Open a repository

This example opens a public repository of ERA5 weather data, which needs no credentials.

1. Choose **File > Import > HDF5/N5/Zarr/OME-NGFF ...**.
2. Paste this URL into the dataset field:

    ```text
    s3://icechunk-public-data/v1/era5_weatherbench2|icechunk://branch.main/1x721x1440
    ```

3. Click **Detect datasets**. The tree lists the repository's arrays.
4. Select `2m_temperature`, tick **Open as virtual**, and click **OK**. Without **Open as virtual**, Fiji tries to
   read the whole array, about 2.3 TB, into memory.

The image is air temperature 2 m above the ground, in kelvin, with one 1440 × 721 slice per hour.

- BigDataViewer opens the same URLs through **Plugins > BigDataViewer > HDF5/N5/Zarr/OME-NGFF Viewer**. It reads
  only the chunks on screen, so it suits multiscale and whole-slide OME-Zarr images; the importer suits a single
  image.
- [Open from a URL](n5.md#open-from-a-url) has the URL syntax, other storage locations and credentials.
- [Segment nuclei with StarDist](stardist.md) is a full example: it opens a microscopy image from Arraylake and
  segments it through Appose.

## Save into a repository

**File > Save As > icechunk...** saves the active image as OME-Zarr and commits it on a branch you choose.
[Save results into icechunk](write-back.md) walks through saving an image and two segmentations, then reopening an
earlier one by its snapshot ID.

## Troubleshooting

### The dialog reads the URL as a local file path

Fiji is still using its own n5-ij, or was not restarted. Check that `jars/` holds exactly one `n5-ij-*.jar` and one
`n5-universe-*.jar`, the ones built above, then restart Fiji.

### `UnsatisfiedLinkError`: interface version mismatch

The error reads `the icechunk native library has interface version N but this jar needs version M`. The icechunk-java
jar and the native library that loaded come from different builds.

- Look in `jars/` for a second `icechunk-java-*.jar` from an earlier build and remove it.
- The icechunk-arraylake-java jar carries its own native library, which loads in place of the one in icechunk-java.
  Rebuild it against the same icechunk-java sources.
