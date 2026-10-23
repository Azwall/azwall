#!/usr/bin/env bash
# Cross-compiles one Barretenberg release for Android with the NDK and wraps it into the shared
# library the prover loads: vendor/bb-shared/<abi>/libbb_v<release>.so.
#
# Usage: scripts/build-bb-android.sh <release|all> [abi...]     abi: arm64-v8a (default), x86_64
#   scripts/build-bb-android.sh 6.0.0 arm64-v8a x86_64
#   scripts/build-bb-android.sh all                       # every release in networks.json
#
# Sources are cloned (sparse) into vendor/aztec-packages-v<release> at tag v<release>. About
# 10 min per ABI and release on an 8-core machine; only rerun when networks.json changes.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RELEASE=${1:?usage: build-bb-android.sh <release|all> [abi...]}
shift
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)

if [ "$RELEASE" = all ]; then
  . "$ROOT/scripts/lib/bb-sources.sh"
  for r in $(bb_releases "$ROOT"); do
    "$0" "$r" "${ABIS[@]}"
  done
  exit 0
fi

STATIC="$ROOT/vendor/bb-static/$RELEASE"
API=${ANDROID_API:-24}
JOBS=${JOBS:-$(nproc)}
: "${NDK_HOME:?set NDK_HOME to your Android NDK (e.g. \$ANDROID_HOME/ndk/27.3.13750724)}"
TOOLCHAIN="$NDK_HOME/build/cmake/android.toolchain.cmake"
command -v cmake >/dev/null && command -v ninja >/dev/null || { echo "error: cmake and ninja are required" >&2; exit 1; }

# 1. Sources at the release tag, with the generated constants (scripts/lib/bb-sources.sh).
. "$ROOT/scripts/lib/bb-sources.sh"
bb_sources "$ROOT" "$RELEASE"

# 2. Static build per ABI, hidden visibility, then the self-contained shared library.
for ABI in "${ABIS[@]}"; do
  case "$ABI" in arm64-v8a|x86_64) ;; *) echo "error: unsupported ABI $ABI" >&2; exit 1 ;; esac
  BUILD="$BB/build-android-$ABI"
  cmake -S "$BB" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-g0 -fvisibility=hidden" -DCMAKE_CXX_FLAGS="-g0 -fvisibility=hidden" \
    -DENABLE_PIC=ON -DBB_LITE=ON -DAVM=OFF -DMULTITHREADING=ON -DOMP_MULTITHREADING=OFF \
    -DHAVE_STD_REGEX=ON -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
  # libbb-external.a leaves libdeflate unresolved (upstream's release archive does too).
  cmake --build "$BUILD" --target bb-external libdeflate_static -j "$JOBS"
  mkdir -p "$STATIC/$ABI"
  cp "$BUILD/lib/libbb-external.a" "$BUILD/_deps/libdeflate-build/libdeflate.a" "$STATIC/$ABI/"
  "$ROOT/scripts/build-bb-shared.sh" "$STATIC/$ABI" "$ROOT/vendor/bb-shared/$ABI/libbb_v$RELEASE.so" "$ABI"
done
