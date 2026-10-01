"""Check the commit the Java interop test made on top of write_repo.py's repository.

Usage: check_repo.py PATH
"""

import sys

import icechunk as ic
import numpy as np
import zarr

repo = ic.Repository.open(ic.local_filesystem_storage(sys.argv[1]))
messages = [snapshot.message for snapshot in repo.ancestry(branch="main")]
assert messages[0] == "java wrote this", messages

session = repo.readonly_session(branch="main")
root = zarr.open_group(store=session.store, mode="r")
np.testing.assert_array_equal(root["from_java"][:], np.arange(12, dtype="int16") - 6)

old = repo.readonly_session(tag="from-python")
assert "from_java" not in zarr.open_group(store=old.store, mode="r")
print("ok")
