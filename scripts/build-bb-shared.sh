#!/usr/bin/env bash
# Wraps a static Barretenberg build (scripts/build-bb-android.sh calls this) into a self-contained shared library that exports
# only azbb_api/azbb_free, so several Barretenberg releases can be loaded side by side in one process (dlopen,
# RTLD_LOCAL). bbapi/bbfree are compiled hidden (-fvisibility=hidden), so a two-line C shim re-exports them under
# names of our own. Each library carries its own libc++ (static) and keeps every other symbol local.
#
# Usage: scripts/build-bb-shared.sh <static dir> <output .so> [abi]
#   scripts/build-bb-shared.sh vendor/bb-static/6.0.0/arm64-v8a vendor/bb-shared/arm64-v8a/libbb_v6.0.0.so
# The output name must stay libbb_v<release>.so: rust/azwall-prover/src/library.rs looks it up by that name.
# Segments are 16 KB aligned: Android 15+ devices with 16 KB pages, and Google Play, require it.
set -euo pipefail
IN=$1 OUT=$2 ABI=${3:-arm64-v8a}
API=${ANDROID_API:-24}
: "${NDK_HOME:?set NDK_HOME}"
# The NDK ships its LLVM toolchain per host; macOS hosts (Intel and Apple Silicon) use darwin-x86_64.
case "$(uname -s)" in Darwin) HOST_TAG=darwin-x86_64 ;; *) HOST_TAG=linux-x86_64 ;; esac
TC="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
case "$ABI" in arm64-v8a) TRIPLE=aarch64-linux-android ;; x86_64) TRIPLE=x86_64-linux-android ;; *) echo "unsupported ABI $ABI" >&2; exit 1 ;; esac
mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
printf '{ global: azbb_api; azbb_free; local: *; };\n' > "$TMP/exports.map"
cat > "$TMP/shim.c" <<'C'
#include <stddef.h>
#include <stdint.h>
void bbapi(const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len);
void bbfree(void *ptr);
__attribute__((visibility("default"))) void azbb_api(const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len) { bbapi(in, in_len, out, out_len); }
__attribute__((visibility("default"))) void azbb_free(void *ptr) { bbfree(ptr); }
C
"$TC/clang" --target="$TRIPLE$API" -fPIC -O2 -c "$TMP/shim.c" -o "$TMP/shim.o"
"$TC/clang++" --target="$TRIPLE$API" -shared -o "$OUT" \
  "$TMP/shim.o" "$IN/libbb-external.a" "$IN/libdeflate.a" \
  -static-libstdc++ -Wl,--version-script="$TMP/exports.map" -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,noexecstack -Wl,-s \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -llog -lm
echo "exports: $("$TC/llvm-readelf" --dyn-syms "$OUT" | awk '$5=="GLOBAL" && $7!="UND" {print $8}' | sort -u | tr '\n' ' ')"
echo "-> $OUT ($(du -h "$OUT" | cut -f1))"
