#!/usr/bin/env bash
# Cross-compiles the Rust core for Android and stages every native library the app ships:
#   android/core/src/main/jniLibs/<abi>/libazwall_ffi.so      (uniffi, JNA)
#   android/core/src/main/jniLibs/<abi>/libbb_v<release>.so   (one per Barretenberg release in networks.json)
#
# Usage: scripts/build-android.sh [--release] [abi...]     abi: arm64-v8a (default), x86_64
# Needs cargo-ndk (cargo install cargo-ndk) and NDK_HOME (or ANDROID_NDK_HOME).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

PROFILE=()
if [ "${1:-}" = "--release" ]; then PROFILE=(--release); shift; fi
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)
: "${NDK_HOME:=${ANDROID_NDK_HOME:-}}"
[ -n "$NDK_HOME" ] || { echo "error: set NDK_HOME" >&2; exit 1; }
export ANDROID_NDK_HOME=$NDK_HOME

JNI=android/core/src/main/jniLibs
TARGETS=()
for abi in "${ABIS[@]}"; do TARGETS+=(-t "$abi"); done
cargo ndk "${TARGETS[@]}" --platform 28 -o "$JNI" build -p azwall-ffi --lib "${PROFILE[@]}"

# Barretenberg releases, built by scripts/build-bb-android.sh + scripts/build-bb-shared.sh: exactly those
# networks.json uses. A missing one fails the build (the prover could not prove on that network), and older
# releases are dropped from the APK.
RELEASES=$(python3 -c 'import json; print("\n".join(sorted({n["barretenberg"] for n in json.load(open("networks.json"))["networks"]})))')
for abi in "${ABIS[@]}"; do
  rm -f "$JNI/$abi"/libbb_v*.so
  for release in $RELEASES; do
    lib=vendor/bb-shared/$abi/libbb_v$release.so
    [ -e "$lib" ] || { echo "error: $lib is missing: run scripts/build-bb-android.sh $release $abi" >&2; exit 1; }
    cp "$lib" "$JNI/$abi/"
  done
done

scripts/gen-bindings.sh kotlin
echo "Android native libraries staged in $JNI"
