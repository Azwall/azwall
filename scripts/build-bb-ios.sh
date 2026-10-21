#!/usr/bin/env bash
# Cross-compiles one Barretenberg release for iOS (device and Apple Silicon simulator) and wraps it into the
# framework the prover loads from the app: vendor/bb-ios/AzBB_<release>.xcframework, with every '.' and '-' of the
# release turned into '_' (a framework name may not hold them; rust/azwall-prover/src/library.rs looks it up).
# The framework exports only azbb_api/azbb_free (scripts/build-bb-shared.sh does the same for Android), so several
# releases load side by side. macOS with Xcode, cmake and ninja only.
#
# Usage: scripts/build-bb-ios.sh <release|all>
#   scripts/build-bb-ios.sh 6.0.0-rc.6
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RELEASE=${1:?usage: build-bb-ios.sh <release|all>}
[ "$(uname -s)" = Darwin ] || { echo "error: iOS builds need macOS and Xcode" >&2; exit 1; }
command -v cmake >/dev/null && command -v ninja >/dev/null || { echo "error: cmake and ninja are required" >&2; exit 1; }
. "$ROOT/scripts/lib/bb-sources.sh"

if [ "$RELEASE" = all ]; then
  for r in $(bb_releases "$ROOT"); do "$0" "$r"; done
  exit 0
fi

MIN_IOS=17.0
JOBS=${JOBS:-$(sysctl -n hw.ncpu)}
NAME="AzBB_${RELEASE//[.-]/_}"
OUT="$ROOT/vendor/bb-ios/$NAME.xcframework"
WORK="$ROOT/vendor/bb-ios/work-$RELEASE"
bb_sources "$ROOT" "$RELEASE"

# Static Barretenberg for one SDK (iphoneos, iphonesimulator), hidden visibility.
build_static() {
  local sdk=$1 build="$BB/build-ios-$1"
  cmake -S "$BB" -B "$build" -G Ninja \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=$MIN_IOS -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-g0 -fvisibility=hidden" -DCMAKE_CXX_FLAGS="-g0 -fvisibility=hidden" \
    -DENABLE_PIC=ON -DBB_LITE=ON -DAVM=OFF -DMULTITHREADING=ON -DOMP_MULTITHREADING=OFF \
    -DHAVE_STD_REGEX=ON -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
  cmake --build "$build" --target bb-external libdeflate_static -j "$JOBS"
}

# The dynamic framework around the static libraries: two exported symbols, install name under @rpath.
wrap_framework() {
  local sdk=$1 target=$2 build="$BB/build-ios-$1" fw="$WORK/$1/$NAME.framework"
  mkdir -p "$fw"
  printf '_azbb_api\n_azbb_free\n' > "$WORK/exports.txt"
  cat > "$WORK/shim.c" <<'C'
#include <stddef.h>
#include <stdint.h>
void bbapi(const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len);
void bbfree(void *ptr);
__attribute__((visibility("default"))) void azbb_api(const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len) { bbapi(in, in_len, out, out_len); }
__attribute__((visibility("default"))) void azbb_free(void *ptr) { bbfree(ptr); }
C
  # The shim is C: compiled apart, as clang++ would mangle the exported names.
  xcrun --sdk "$sdk" clang -target "$target" -O2 -c "$WORK/shim.c" -o "$WORK/shim-$sdk.o"
  xcrun --sdk "$sdk" clang++ -target "$target" -dynamiclib -O2 \
    "$WORK/shim-$sdk.o" "$build/lib/libbb-external.a" "$build/_deps/libdeflate-build/libdeflate.a" \
    -exported_symbols_list "$WORK/exports.txt" -install_name "@rpath/$NAME.framework/$NAME" \
    -Wl,-dead_strip -o "$fw/$NAME"
  xcrun strip -x "$fw/$NAME"
  cat > "$fw/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>$NAME</string>
  <key>CFBundleIdentifier</key><string>dev.azwall.bb.${RELEASE//[.-]/-}</string>
  <key>CFBundleName</key><string>$NAME</string>
  <key>CFBundlePackageType</key><string>FMWK</string>
  <key>CFBundleShortVersionString</key><string>${RELEASE%%-*}</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>MinimumOSVersion</key><string>$MIN_IOS</string>
</dict></plist>
PLIST
  echo "exports ($sdk): $(xcrun nm -gU "$fw/$NAME" | awk '{print $3}' | tr '\n' ' ')"
}

build_static iphoneos
build_static iphonesimulator
wrap_framework iphoneos "arm64-apple-ios$MIN_IOS"
wrap_framework iphonesimulator "arm64-apple-ios$MIN_IOS-simulator"
rm -rf "$OUT"
xcodebuild -create-xcframework -framework "$WORK/iphoneos/$NAME.framework" -framework "$WORK/iphonesimulator/$NAME.framework" -output "$OUT"
echo "-> $OUT ($(du -sh "$OUT" | cut -f1))"
