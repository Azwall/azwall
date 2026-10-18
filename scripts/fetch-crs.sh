#!/usr/bin/env bash
# Downloads the CRS prefixes Chonk proving needs into assets/crs/, bundled by both apps.
# The files are universal (BN254, Grumpkin): every Barretenberg release uses the same ones.
# Sizes must match rust/azwall-prover/src/srs.rs.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/assets/crs"
HOST=${CRS_HOST:-https://crs.aztec-cdn.foundation}
mkdir -p "$OUT"

G1_POINTS=$((2 ** 19))
GRUMPKIN_POINTS=$((2 ** 16))

fetch_prefix() {
  local file=$1 bytes=$2 dest="$OUT/$1"
  if [ -f "$dest" ] && [ "$(wc -c < "$dest")" -eq "$bytes" ]; then
    echo "ok      $file (cached)"
    return
  fi
  echo "fetch   $file ($bytes bytes)"
  curl --fail --silent --show-error --location --retry 3 --range "0-$((bytes - 1))" -o "$dest.part" "$HOST/$file"
  [ "$(wc -c < "$dest.part")" -eq "$bytes" ] || { echo "error: $file has the wrong size" >&2; rm -f "$dest.part"; exit 1; }
  mv "$dest.part" "$dest"
}

fetch_prefix g1_compressed.dat $((G1_POINTS * 32))
fetch_prefix g2.dat 128
fetch_prefix grumpkin_g1_v2.dat $((GRUMPKIN_POINTS * 64))

cd "$OUT"
if command -v sha256sum >/dev/null; then
  sha256sum --check --quiet "$ROOT/scripts/crs.sha256"
else
  shasum -a 256 --check --quiet "$ROOT/scripts/crs.sha256"
fi
echo "CRS hashes match scripts/crs.sha256"
