# How the pieces fit

icechunk-java connects two groups of projects. On one side is icechunk, a versioned store for Zarr data, written in
Rust and used mostly from Python. On the other are the Java libraries and applications that read chunked arrays,
mostly N5 and the Fiji and Paintera tools built on it.

## The projects

### Storage

- [Zarr](https://zarr.dev) is a format for chunked, compressed N-dimensional arrays. An array is split into chunks
  stored as separate objects, and arrays are arranged in groups, like directories. A Zarr store maps keys such as
  `temperature/zarr.json` or `temperature/c/0/0` to bytes. icechunk holds [Zarr version 3](https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html)
  only.
- [icechunk](https://icechunk.io) is a transactional storage engine for Zarr, written in Rust. A repository stores a
  Zarr hierarchy in a local directory or object storage and keeps its history. Each commit makes a snapshot of the
  whole hierarchy; branches and tags name snapshots, as in git. A repository can also hold virtual chunks: references
  to byte ranges inside other files, such as NetCDF, HDF5 or TIFF files in a bucket, read from where they already
  live.
- [icechunk-python](https://pypi.org/project/icechunk/) is the Python binding for icechunk, used with
  [zarr-python](https://zarr.readthedocs.io) and [xarray](https://xarray.dev).
- [Arraylake](https://earthmover.io) is Earthmover's managed service that hosts icechunk repositories and holds
  their credentials. A separate project, icechunk-arraylake-java, opens Arraylake repositories as icechunk-java
  `Repository` objects.

### Java array libraries

- [zarr-java](https://github.com/zarr-developers/zarr-java) is a Zarr library for Java that reads and writes arrays
  through a store interface.
- [N5](https://github.com/saalfeldlab/n5) is a Java API for chunked N-dimensional arrays from the Saalfeld lab at
  HHMI Janelia. It does its I/O through a `KeyValueAccess`, an interface for reading and writing bytes at paths. N5's
  libraries implement it for local files, S3, Google Cloud Storage and HTTP.
- [n5-zarr](https://github.com/saalfeldlab/n5-zarr) is N5's reader and writer for the Zarr format, version 2 and 3.
- [n5-universe](https://github.com/saalfeldlab/n5-universe) opens N5, Zarr and HDF5 containers from a path or URL.
  Its `N5Factory` picks the storage backend and format for the location, and it reads OME-NGFF metadata.

### Applications

- [Fiji](https://fiji.sc) is a distribution of [ImageJ](https://imagej.net), the image analysis application widely used
  in microscopy and bioimaging, with plugins bundled.
- [n5-ij](https://github.com/saalfeldlab/n5-ij) is Fiji's importer and exporter for HDF5, N5, Zarr and OME-NGFF
  (File > Import > HDF5/N5/Zarr/OME-NGFF). It opens locations through `N5Factory`.
- [BigDataViewer](https://imagej.net/plugins/bdv/) is a Fiji viewer for large and multiscale images, which loads only
  the chunks on screen. Its [N5 viewer](https://github.com/saalfeldlab/n5-viewer) opens N5 and Zarr containers
  through n5-ij's dialog and `N5Factory`
  (Plugins > BigDataViewer > HDF5/N5/Zarr/OME-NGFF Viewer).
- [Paintera](https://github.com/saalfeldlab/paintera) is a tool for painting and proofreading labels in large 3D
  images, built on BigDataViewer and N5. icechunk-java builds against the n5, n5-zarr and n5-universe versions it
  pins.
- [OME-NGFF](https://ngff.openmicroscopy.org), also called OME-Zarr, is the Open Microscopy Environment's
  convention for storing images in Zarr: multiscale pyramids, axes, channels and labels. An OME-Zarr image stored in
  an icechunk repository is still OME-Zarr, so n5-ij and the N5 viewer read its metadata as usual.

## From Fiji to storage

Fiji tools open an icechunk repository from a URL. The URL names the repository's location, then after
`|icechunk:` the version and the node inside it:

```text
s3://bucket/repo|icechunk://branch.main/em/raw
gs://bucket/repo|icechunk://tag.v1
/data/repo|icechunk://GQQFH5G3AXKWZR5H33M0/labels
```

The version is `//branch.NAME`, `//tag.NAME` or `//SNAPSHOT_ID`; without one the URL opens the main branch. The
syntax comes from the draft [URL pipeline specification](https://github.com/jbms/url-pipeline), which is not yet an
official Zarr standard.
[Open from a URL](n5.md#open-from-a-url) has the full syntax and how credentials are found. A read of one chunk goes
down this stack:

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

URLs open read-only, because a write needs a commit and a URL has no place for one. To write from N5, build an
`IcechunkKeyValueAccess` on a writable session in code and commit it, as in [Write and commit](n5.md#write-and-commit).

An Arraylake repository opens the same way, with the location written as `al:org/repo`, once
icechunk-arraylake-java is on the classpath. It supplies the repository's storage and credentials, including those
for its virtual chunks.

## Which module to use

| Goal | Modules |
|---|---|
| Read and write Zarr keys and bytes from your own code, or write an adapter for another Zarr library | `icechunk-java` |
| Read and write arrays with zarr-java | `icechunk-java`, `icechunk-zarr-java` |
| Read and write arrays with N5 and n5-zarr, or in an application built on them, such as Paintera | `icechunk-java`, `icechunk-n5` |
| Open repositories by URL in Fiji's importer, BigDataViewer's N5 viewer, or other code that uses `N5Factory` | `icechunk-java`, `icechunk-n5`, `icechunk-n5-universe` |

Add `icechunk-n5-codecs` to either N5 row when a repository's arrays use the `numcodecs.pcodec` or `numcodecs.zlib`
codecs, which zarr-python can write and n5-zarr cannot decode on its own.

Opening by URL needs pieces that are not in released versions of n5-universe and n5-ij yet:

- `icechunk-n5-universe` implements a `KeyValueAccessProvider` interface that no released n5-universe has. It builds
  only with the `-Pn5-universe-provider` Maven profile, against an n5-universe build that includes the interface.
- n5-ij's dataset dialog needs a change, not yet released, to accept URLs that contain `|`.

Until those are released, Fiji opens icechunk URLs only with those n5-universe and n5-ij builds in place of the ones
it ships. [Changes in other projects](upstream.md) links the branches that hold them, says which change each goal
needs, and how to install them.
