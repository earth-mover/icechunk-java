"""Check the arrays the N5 interop test wrote through n5-zarr.

Usage: check_n5_repo.py PATH
"""

import sys

import icechunk as ic
import numpy as np
import zarr

repo = ic.Repository.open(ic.local_filesystem_storage(sys.argv[1]))
root = zarr.open_group(store=repo.readonly_session(branch="main").store, mode="r")

em = root["em data"]
assert em.attrs["units"] == "nm", dict(em.attrs)

raw = em["raw"]
assert raw.shape == (8, 10) and raw.dtype == np.uint16, (raw.shape, raw.dtype)
np.testing.assert_array_equal(raw[:], np.arange(80, dtype=np.uint16).reshape(8, 10))

labels = em["labels"]
assert labels.shards == (4, 4) and labels.chunks == (2, 2), (labels.shards, labels.chunks)
np.testing.assert_array_equal(labels[:], np.arange(64, dtype=np.uint8).reshape(8, 8))
print("ok")
