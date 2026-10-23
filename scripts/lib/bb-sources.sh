# Sourced by build-bb-android.sh and build-bb-ios.sh: fetches one Barretenberg release's sources.
#
# bb_sources <root> <release> sets SRC (the aztec-packages checkout) and BB (its barretenberg/cpp). Sources are
# cloned (sparse) into vendor/aztec-packages-v<release> at tag v<release>, and the generated aztec_constants.hpp
# is produced the way upstream CMake does inside a full monorepo checkout (needs Node >= 22).
bb_sources() {
  local root=$1 release=$2
  local tag="v$release"
  SRC="$root/vendor/aztec-packages-$tag"
  BB="$SRC/barretenberg/cpp"
  if [ ! -d "$SRC/.git" ]; then
    echo "cloning aztec-packages $tag (barretenberg/cpp only)"
    git clone --quiet --depth 1 --branch "$tag" --filter=blob:none --sparse \
      https://github.com/AztecProtocol/aztec-packages "$SRC"
    git -C "$SRC" sparse-checkout set barretenberg/cpp
  fi
  # A final release often shares its commit with a nightly and an rc tag, so compare commits, not describe's pick.
  if ! git -C "$SRC" tag --points-at HEAD | grep -qx "$tag"; then
    echo "error: $SRC is at '$(git -C "$SRC" rev-parse --short HEAD)', not $tag; delete it to re-clone" >&2
    return 1
  fi

  local constants="$BB/src/barretenberg/aztec/aztec_constants.hpp"
  if [ ! -f "$constants" ]; then
    git -C "$SRC" sparse-checkout set barretenberg/cpp protocol/constants-codegen \
      noir-projects/fnd/noir-protocol-circuits/crates/types/src
    "$SRC/protocol/constants-codegen/scripts/generate.sh" \
      --cpp "$constants" --selection "$BB/scripts/constants-codegen/cpp.json" 2>/dev/null
    grep -q '#define ARCHIVE_HEIGHT' "$constants" || { echo "error: constants generation failed" >&2; return 1; }
    git -C "$SRC" sparse-checkout set barretenberg/cpp
  fi
}

# Every Barretenberg release networks.json uses, one per line.
bb_releases() {
  python3 -c "import json;print('\n'.join(sorted({n['barretenberg'] for n in json.load(open('$1/networks.json'))['networks']})))"
}
