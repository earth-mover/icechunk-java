"""Write the repository the Java interop test reads.

Usage: write_repo.py PATH
"""

import sys

import icechunk as ic
import numpy as np
import zarr

storage = ic.local_filesystem_storage(sys.argv[1])
repo = ic.Repository.create(storage)
session = repo.writable_session("main")
root = zarr.group(store=session.store)
temperature = root.create_array(
    "temperature",
    shape=(6, 8),
    chunks=(3, 4),
    dtype="float64",
    fill_value=float("nan"),
)
temperature[:] = np.arange(48, dtype="float64").reshape(6, 8) / 2
counts = root.create_array(
    "counts",
    shape=(10,),
    chunks=(4,),
    shards=(8,),
    dtype="int32",
    fill_value=0,
)
counts[:] = np.arange(10, dtype="int32") * 3
first = session.commit("python wrote this")
repo.create_tag("from-python", snapshot_id=first)
