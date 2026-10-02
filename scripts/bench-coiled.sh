#!/usr/bin/env bash
# Run the benchmarks on a Coiled VM instead of this machine.
#
# Builds the benchmarks jar locally (it is platform independent), uploads it with the
# native crate's sources, builds the native library on the VM, and runs the JMH grid and
# the memory probe there. Results print to the terminal and are saved in
# benchmarks/target/coiled-run.log.
#
# Usage: scripts/bench-coiled.sh [VM_TYPE] [extra JMH arguments...]
#   VM_TYPE defaults to c7i.4xlarge (16 vCPU, x86_64).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
vm_type="${1:-c7i.4xlarge}"
shift || true
jmh_args="$*"

(cd "$root" && pixi run mvn -B -q package -DskipTests -pl benchmarks -am)

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/native"
cp -R "$root/native/Cargo.toml" "$root/native/Cargo.lock" "$root/native/src" "$stage/native/"
cp "$root/rust-toolchain.toml" "$stage/"
cp "$root/benchmarks/target/benchmarks.jar" "$stage/"

cat > "$stage/run.sh" <<SCRIPT
set -euo pipefail
apt-get update -qq && apt-get install -y -qq openjdk-17-jdk-headless procps > /dev/null
cargo build --release --manifest-path native/Cargo.toml
lib=native/target/release
mkdir -p results
java -jar benchmarks.jar -jvmArgsAppend -Dicechunk.native.dir=\$lib -prof gc \
    -rf json -rff results/jmh-t1.json $jmh_args | tee results/jmh-t1.log
java -jar benchmarks.jar 'StoreBenchmark.(get|getInto|getBuffer|callOverhead|exists|set|setDirect)\$' -t 8 \
    -jvmArgsAppend -Dicechunk.native.dir=\$lib -prof gc \
    -rf json -rff results/jmh-t8.json $jmh_args | tee results/jmh-t8.log
for mode in get getInto getBuffer set setDirect; do
    java -Xmx2g -Dicechunk.native.dir=\$lib -cp benchmarks.jar \
        io.earthmover.icechunk.benchmarks.MemoryProbe \$mode 4 1024 2>/dev/null | tail -1
done | tee results/memory.txt
echo "=== results/jmh-t1.json"; cat results/jmh-t1.json
echo "=== results/jmh-t8.json"; cat results/jmh-t8.json
SCRIPT

cd "$stage"
coiled run --container rust:1.95-bookworm --root --vm-type "$vm_type" \
    --file native/ --file rust-toolchain.toml --file benchmarks.jar --file run.sh \
    -- bash run.sh 2>&1 | tee "$root/benchmarks/target/coiled-run.log"
