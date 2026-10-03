"""Write Zarr v3 arrays that use numcodecs codecs, for the n5 codec tests to read.

Each array has shape (7, 10) in chunks of (3, 4), so the last row and column of
chunks are partial. Element (y, x) is x + 10 * y, scaled by 0.25 and offset by -3
for floating-point types, and offset by -30 for signed integer types.

Usage: write_codec_arrays.py PATH
"""

import sys

import numpy as np
import zarr
from zarr.codecs.numcodecs import PCodec, Zlib

root = zarr.open_group(sys.argv[1], mode="w", zarr_format=3)
ramp = np.arange(70).reshape(7, 10)


def values(dtype):
    dtype = np.dtype(dtype)
    if dtype.kind == "f":
        return (ramp * 0.25 - 3).astype(dtype)
    if dtype.kind == "i":
        return (ramp - 30).astype(dtype)
    return ramp.astype(dtype)


def write(name, dtype, **codecs):
    array = root.create_array(name, shape=(7, 10), chunks=(3, 4), dtype=dtype, **codecs)
    array[:] = values(dtype)


for dtype in ["uint16", "int16", "uint32", "int32", "uint64", "int64", "float32", "float64"]:
    write(f"pcodec/{dtype}", dtype, serializer=PCodec(level=8), compressors=None)
write("pcodec_zlib/float32", "float32", serializer=PCodec(), compressors=[Zlib(level=5)])
write("zlib/uint16", "uint16", compressors=[Zlib(level=5)])
print("ok")
