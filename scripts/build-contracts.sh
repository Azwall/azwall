#!/usr/bin/env bash
# Compiles the Noir contracts in contracts/ with the Aztec toolchain of the network they target, and copies the
# artifacts where the PXE hosts import them. Needs `aztec` (aztec-up) at the version below.
set -euo pipefail
cd "$(dirname "$0")/.."

AZTEC_VERSION="6.0.0-rc.3"
if ! aztec --version 2>/dev/null | grep -q "$AZTEC_VERSION"; then
  echo "aztec $AZTEC_VERSION is required (aztec-up $AZTEC_VERSION)" >&2
  exit 1
fi

(cd contracts/pq_account && aztec-nargo test && aztec compile)
cp contracts/pq_account/target/pq_account_contract-PqAccount.json pxe/v6/src/pq/PqAccount.json
echo "pq_account: $(cd contracts/pq_account && aztec profile gates target | grep '::')"
