#!/usr/bin/env bash
# Generates the uniffi bindings of rust/azwall-ffi from a host build (library mode).
#
# Usage: scripts/gen-bindings.sh kotlin|swift
#   kotlin → android/core/src/generated/kotlin/
#   swift  → ios/AzwallRust/Sources/AzwallCoreFFI/generated/ (Swift) and build/ios/headers/ (C header, modulemap)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LANG_=${1:?usage: gen-bindings.sh kotlin|swift}
cd "$ROOT"

cargo build --quiet -p azwall-ffi --lib
case "$(uname -s)" in Darwin) LIB=target/debug/libazwall_ffi.dylib ;; *) LIB=target/debug/libazwall_ffi.so ;; esac
bindgen() { cargo run --quiet -p azwall-ffi --bin uniffi-bindgen -- generate --library "$LIB" --language "$1" --out-dir "$2" --no-format; }

case "$LANG_" in
  kotlin)
    OUT=android/core/src/generated/kotlin
    rm -rf "$OUT" && mkdir -p "$OUT"
    bindgen kotlin "$OUT"
    ;;
  swift)
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    bindgen swift "$TMP"
    SWIFT_OUT=ios/AzwallRust/Sources/AzwallCoreFFI/generated
    HEADERS=build/ios/headers
    rm -rf "$SWIFT_OUT" "$HEADERS" && mkdir -p "$SWIFT_OUT" "$HEADERS"
    cp "$TMP"/*.swift "$SWIFT_OUT/"
    cp "$TMP"/*.h "$HEADERS/"
    cp "$TMP"/*.modulemap "$HEADERS/module.modulemap"
    ;;
  *) echo "unknown language $LANG_ (kotlin|swift)" >&2; exit 1 ;;
esac
echo "generated $LANG_ bindings"
