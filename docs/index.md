# icechunk-java

!!! warning "Experimental, not officially supported"
    This is not an Earthmover product and is not part of the icechunk project's supported surface. Nothing is
    published to Maven Central, the API will change without notice, and there is no guarantee of fixes or
    compatibility. Do not use it for data you cannot afford to lose.

Java bindings for [icechunk](https://icechunk.io), the transactional storage engine for [Zarr](https://zarr.dev).
They call the icechunk Rust library through JNI, so Java programs get the same repositories, branches, tags and
commits as [icechunk-python](https://pypi.org/project/icechunk/), and read and write the same data.

| Artifact | What it contains |
|---|---|
| `icechunk-java` | `Storage`, `Repository`, `Session` and `Store`. |
| `icechunk-zarr-java` | `IcechunkZarrStore`, for [zarr-java](https://github.com/zarr-developers/zarr-java). |
| `icechunk-n5` | `IcechunkKeyValueAccess`, for [N5](https://github.com/saalfeldlab/n5) and [n5-zarr](https://github.com/saalfeldlab/n5-zarr). See [N5](n5.md). |
| `icechunk-n5-codecs` | The `numcodecs.pcodec` and `numcodecs.zlib` codecs for n5-zarr. See [N5](n5.md#numcodecs-codecs). |
| `icechunk-n5-universe` | `IcechunkKeyValueAccessProvider`, which opens repositories from URLs through n5-universe's `N5Factory`. Builds only with `-Pn5-universe-provider`. |

[How the pieces fit](ecosystem.md) explains where these modules sit among icechunk, Zarr, N5 and Fiji.

This site covers the N5 adapter on the [N5](n5.md) page. For the Java API itself (storage, repositories, sessions,
branches, tags and garbage collection), see the repository's `README.md`.
