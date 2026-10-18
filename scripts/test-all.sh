#!/usr/bin/env bash
# Runs every test suite available on this machine, plus the code size rules.
#   Rust:    cargo test (live RPC tests stay ignored; add LIVE=1 to run them against .env)
#   Android: Gradle unit tests (JVM)
#   iOS:     swift test in ios/AzwallKit (when a Swift toolchain is installed)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

scripts/check-limits.py
cargo test --workspace
if [ "${LIVE:-0}" = 1 ]; then
  cargo test -p azwall-core --test rpc_live -- --ignored --nocapture
fi
(cd android && ./gradlew --quiet test)
if command -v swift >/dev/null; then
  (cd ios/AzwallKit && swift test)
else
  echo "skipping iOS tests: no Swift toolchain"
fi
