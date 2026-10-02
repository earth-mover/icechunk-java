#!/usr/bin/env bash
# Fetch icechunk's checked-in compatibility repositories at the revision the native
# crate resolves to, so the Java tests read the same repositories icechunk's own
# test_can_read_old.py does.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/target/icechunk-fixtures"

# The resolved icechunk package in Cargo.lock: a git source ends in "#<commit>", and a
# registry release is tagged "v<version>" in the icechunk repository.
package="$(awk '/^\[\[package\]\]/ { found = 0 } /^name = "icechunk"$/ { found = 1 } found' "$root/native/Cargo.lock")"
source="$(printf '%s\n' "$package" | sed -n 's/^source = "\(.*\)"$/\1/p')"
version="$(printf '%s\n' "$package" | sed -n 's/^version = "\(.*\)"$/\1/p')"
case "$source" in
    git+*) rev="${source##*#}" ;;
    registry+*) rev="v$version" ;;
    *) echo "cannot find the icechunk package in native/Cargo.lock" >&2; exit 1 ;;
esac
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
