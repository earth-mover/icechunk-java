#!/usr/bin/env bash
# Copy the per-platform native libraries that release.yml's native jobs uploaded into
# icechunk-java's resources, where NativeLoader looks for them in the jar.
#
# Usage: scripts/bundle-native-libs.sh <dir holding native-<platform>/ directories>
set -euo pipefail

src="$1"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/icechunk-java/src/main/resources/io/icechunk/native"

for dir in "$src"/native-*; do
    platform="${dir##*/native-}"
    mkdir -p "$dest/$platform"
    cp "$dir"/* "$dest/$platform/"
done
find "$dest" -type f | sort
