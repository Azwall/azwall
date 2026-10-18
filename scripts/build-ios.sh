#!/usr/bin/env bash
# Builds the Rust core for iOS (device + simulator) into build/ios/AzwallFFI.xcframework and
# generates the Swift bindings. macOS with Xcode only.
#
# Usage: scripts/build-ios.sh [--release]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
[ "$(uname -s)" = Darwin ] || { echo "error: iOS builds need macOS and Xcode" >&2; exit 1; }

PROFILE=debug
FLAGS=()
if [ "${1:-}" = "--release" ]; then PROFILE=release; FLAGS=(--release); fi

TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios)
rustup target add "${TARGETS[@]}" >/dev/null
for t in "${TARGETS[@]}"; do
  cargo build -p azwall-ffi --lib --target "$t" "${FLAGS[@]}"
done

scripts/gen-bindings.sh swift

LIB=libazwall_ffi.a
SIM=build/ios/sim
mkdir -p "$SIM"
lipo -create "target/aarch64-apple-ios-sim/$PROFILE/$LIB" "target/x86_64-apple-ios/$PROFILE/$LIB" -output "$SIM/$LIB"
rm -rf build/ios/AzwallFFI.xcframework
xcodebuild -create-xcframework \
  -library "target/aarch64-apple-ios/$PROFILE/$LIB" -headers build/ios/headers \
  -library "$SIM/$LIB" -headers build/ios/headers \
  -output build/ios/AzwallFFI.xcframework
echo "built build/ios/AzwallFFI.xcframework"
