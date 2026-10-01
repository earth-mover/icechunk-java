#!/usr/bin/env bash
# Fetch icechunk's checked-in compatibility repositories at the revision the native
# crate is pinned to, so the Java tests read the same repositories icechunk's own
# test_can_read_old.py does.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
rev="$(sed -n 's/^icechunk = .*rev = "\([0-9a-f]*\)".*/\1/p' "$root/native/Cargo.toml")"
dest="$root/target/icechunk-fixtures"

if [ -z "$rev" ]; then
    echo "cannot find the icechunk rev in native/Cargo.toml" >&2
    exit 1
fi
if [ -f "$dest/REV" ] && [ "$(cat "$dest/REV")" = "$rev" ]; then
    exit 0
fi

rm -rf "$dest"
mkdir -p "$dest"
git -C "$dest" init -q
git -C "$dest" remote add origin https://github.com/earth-mover/icechunk
git -C "$dest" sparse-checkout set icechunk-python/tests/data
git -C "$dest" fetch -q --depth 1 --filter=blob:none origin "$rev"
git -C "$dest" checkout -q FETCH_HEAD
echo "$rev" > "$dest/REV"
echo "icechunk fixtures at $rev in $dest/icechunk-python/tests/data"
