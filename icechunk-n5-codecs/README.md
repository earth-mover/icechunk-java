# icechunk-n5-codecs

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

Decoders for two Zarr v3 codecs that zarr-python can write and [n5-zarr](https://github.com/saalfeldlab/n5-zarr), the
Zarr reader of the [N5](https://github.com/saalfeldlab/n5) library, cannot read on its own:

- `numcodecs.pcodec` replaces `bytes` as an array's serializer. It decodes `uint16`, `int16`, `uint32`, `int32`,
  `uint64`, `int64`, `float32` and `float64` arrays. Decoding runs in the icechunk native library, since
  [pcodec](https://github.com/pcodec/pcodec) has no Java implementation. Writing is not supported.
- `numcodecs.zlib` is a compressor that stores each chunk as a zlib stream.

The codecs work on arrays in any store n5-zarr reads, not only icechunk. Use this module when an N5 application, such
as a Fiji plugin, has to read arrays that use them.

The module is a stopgap until these codecs are available from a shared codec package for n5-zarr.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-n5-codecs</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

It depends on [icechunk-java](../icechunk-java), for the native pcodec decoder; that library is needed even when no
icechunk repository is involved. n5 and n5-zarr are `provided`: the application brings them.

## Example

n5 finds codecs through a SciJava annotation index, which this jar carries, so being on the classpath is enough.
Reading a pcodec array from a directory zarr-python wrote needs no code specific to this module:

```java
N5Reader n5 = new ZarrV3KeyValueReader(new FileSystemKeyValueAccess(), "/data/arrays.zarr", new GsonBuilder(), false);
DatasetAttributes attributes = n5.getDatasetAttributes("pcodec/float32");
DataBlock<?> block = n5.readChunk("pcodec/float32", attributes, 0, 0);
```

## More

- [docs/n5.md](../docs/n5.md#numcodecs-codecs) describes the codecs alongside the rest of N5 over icechunk.
