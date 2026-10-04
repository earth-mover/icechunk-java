# How the pieces fit

**icechunk-java connects icechunk, a versioned store for Zarr written in Rust, to Java's array libraries and the Fiji
tools built on them.**

- **Storage side:** Zarr, icechunk, icechunk-python and Arraylake.
- **Java side:** zarr-java, N5, n5-zarr and n5-universe, and the applications on top of them: Fiji, n5-ij,
  BigDataViewer and Paintera.
- **Which module you need** depends on which Java library you use. See [Which module to use](#which-module-to-use).

## The projects

### Storage

- [Zarr](https://zarr.dev) is a format for chunked, compressed N-dimensional arrays. Arrays are split into chunks
  stored as separate objects and arranged in groups, like directories. A Zarr store maps keys such as
  `temperature/zarr.json` or `temperature/c/0/0` to bytes. icechunk holds
  [Zarr version 3](https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html) only.
- [icechunk](https://icechunk.io) is a transactional storage engine for Zarr, written in Rust. A repository keeps a
  Zarr hierarchy and its history: each commit is a snapshot, and branches and tags name snapshots, as in git. Virtual
  chunks read byte ranges inside existing NetCDF, HDF5 or TIFF files where they live.
- [icechunk-python](https://pypi.org/project/icechunk/) is the Python binding for icechunk, used with
  [zarr-python](https://zarr.readthedocs.io) and [xarray](https://xarray.dev).
- [Arraylake](https://earthmover.io) is Earthmover's managed service that hosts icechunk repositories and holds their
  credentials. The separate icechunk-arraylake-java project opens Arraylake repositories as icechunk-java
  `Repository` objects.

### Java array libraries

- [zarr-java](https://github.com/zarr-developers/zarr-java) is a Zarr library for Java that reads and writes arrays
  through a store interface.
- [N5](https://github.com/saalfeldlab/n5) is a Java API for chunked N-dimensional arrays from the Saalfeld lab at
  HHMI Janelia. It does its I/O through a `KeyValueAccess`, an interface for reading and writing bytes at paths,
  which N5's libraries implement for local files, S3, Google Cloud Storage and HTTP.
- [n5-zarr](https://github.com/saalfeldlab/n5-zarr) is N5's reader and writer for Zarr versions 2 and 3.
- [n5-universe](https://github.com/saalfeldlab/n5-universe) opens N5, Zarr and HDF5 containers from a path or URL. Its
  `N5Factory` picks the storage backend and format for the location, and it reads OME-NGFF metadata.

### Applications

- [Fiji](https://fiji.sc) is a distribution of [ImageJ](https://imagej.net), the image analysis application widely
  used in microscopy and bioimaging, with plugins bundled.
- [n5-ij](https://github.com/saalfeldlab/n5-ij) is Fiji's importer and exporter for HDF5, N5, Zarr and OME-NGFF
  (**File > Import > HDF5/N5/Zarr/OME-NGFF ...**). It opens locations through `N5Factory`.
- [BigDataViewer](https://imagej.net/plugins/bdv/) is a Fiji viewer for large and multiscale images that loads only
  the chunks on screen. Its [N5 viewer](https://github.com/saalfeldlab/n5-viewer) opens containers through n5-ij's
  dialog and `N5Factory` (**Plugins > BigDataViewer > HDF5/N5/Zarr/OME-NGFF Viewer**).
- [Paintera](https://github.com/saalfeldlab/paintera) is a tool for painting and proofreading labels in large 3D
  images, built on BigDataViewer and N5. icechunk-java builds against the n5, n5-zarr and n5-universe versions it
  pins.
- [OME-NGFF](https://ngff.openmicroscopy.org), also called OME-Zarr, is the convention for storing microscopy images
  in Zarr: multiscale pyramids, axes, channels and labels. OME-Zarr stored in icechunk is still OME-Zarr, so n5-ij and
  the N5 viewer read its metadata as usual.

## From Fiji to storage

**A Fiji tool opens a repository from a URL such as `s3://bucket/repo|icechunk://branch.main/em/raw`**, and a read of
one chunk goes down this stack. [Open from a URL](n5.md#open-from-a-url) has the URL syntax.

```text
n5-ij importer, or the N5 viewer in BigDataViewer
  │  opens s3://bucket/repo|icechunk://branch.main/em/raw
  ▼
n5-universe          N5Factory asks each KeyValueAccessProvider on the classpath for the URL
  ▼
icechunk-n5-universe IcechunkKeyValueAccessProvider opens the repository and a read-only session
  ▼
n5-zarr              the Zarr v3 reader that N5Factory builds on that session
  ▼
icechunk-n5          IcechunkKeyValueAccess turns N5 paths into Zarr keys
  ▼
icechunk-java        Session and Store, which call the Rust library through JNI
  ▼
icechunk (Rust)      finds the chunk in the snapshot's manifests and fetches it
  │
  ├── repository storage: a local directory, S3, Google Cloud Storage, HTTP, or Arraylake
  └── virtual chunks: byte ranges in other files, read from where they live
```

- **URLs open read-only**, because a write needs a commit and a URL has no place for one. To write from N5, use a
  writable session in code, as in [Write and commit](n5.md#write-and-commit). Fiji writes through
  **File > Save As > icechunk...**, which commits ([Use in Fiji](fiji.md#save-into-a-repository)).
- **Arraylake** repositories open the same way, as `al:org/repo`, once icechunk-arraylake-java is on the classpath. It
  supplies the storage and credentials, including those for virtual chunks.

## Which module to use

| Goal | Modules |
|---|---|
| Read and write Zarr keys and bytes from your own code, or write an adapter for another Zarr library | `icechunk-java` |
| Read and write arrays with zarr-java | `icechunk-java`, `icechunk-zarr-java` |
| Read and write arrays with N5 and n5-zarr, or in an application built on them, such as Paintera | `icechunk-java`, `icechunk-n5` |
| Open repositories by URL in Fiji's importer, BigDataViewer's N5 viewer, or other code that uses `N5Factory` | `icechunk-java`, `icechunk-n5`, `icechunk-n5-universe` |

- **Codecs:** add `icechunk-n5-codecs` to either N5 row when arrays use `numcodecs.pcodec` or `numcodecs.zlib`, which
  zarr-python can write and n5-zarr cannot decode on its own.
- **Opening by URL needs unreleased forks** of n5-universe and n5-ij. [Changes in other projects](upstream.md) lists
  what they change, and [Use in Fiji](fiji.md) installs them.
