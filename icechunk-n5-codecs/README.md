# icechunk-n5-codecs

> [!WARNING]
> Experimental and not officially supported. Nothing is published to Maven Central, and the API will change without
> notice. See the [top-level README](../README.md).

**Decoders that let [n5-zarr](https://github.com/saalfeldlab/n5-zarr) read arrays zarr-python wrote with
`numcodecs.pcodec` or `numcodecs.zlib`.**

- **Any store.** The codecs work wherever n5-zarr reads, not only on icechunk.
- **Classpath only.** n5 finds the codecs through the SciJava annotation index this jar carries, so no code registers
  them.
- **pcodec reads only.** It decodes 16-, 32- and 64-bit integers, `float32` and `float64`, and cannot write.

[numcodecs codecs](../docs/n5.md#numcodecs-codecs) lists the supported data types and which codecs n5-zarr handles
itself.

The module is a stopgap until these codecs are available from a shared codec package for n5-zarr.

## Dependency

```xml
<dependency>
  <groupId>io.earthmover.icechunk</groupId>
  <artifactId>icechunk-n5-codecs</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

- [icechunk-java](../icechunk-java) comes in for the native pcodec decoder, since
  [pcodec](https://github.com/pcodec/pcodec) has no Java implementation. The native library is needed even when no
  icechunk repository is involved.
- n5 and n5-zarr are `provided`: the application brings them.

## Example

Reading a pcodec array from a directory zarr-python wrote needs no code specific to this module:

```java
N5Reader n5 = new ZarrV3KeyValueReader(new FileSystemKeyValueAccess(), "/data/arrays.zarr", new GsonBuilder(), false);
DatasetAttributes attributes = n5.getDatasetAttributes("pcodec/float32");
DataBlock<?> block = n5.readChunk("pcodec/float32", attributes, 0, 0);
```
