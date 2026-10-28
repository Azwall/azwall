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
  # The tag must still name the commit recorded for the release (scripts/bb-commits): a moved tag would change the
  # prover behind every proof without a word.
  local expected
  expected=$(awk -v r="$release" '$1 == r { print $2 }' "$root/scripts/bb-commits")
  if [ -z "$expected" ]; then
    echo "error: no commit recorded for Barretenberg $release in scripts/bb-commits" >&2
    return 1
  fi
  if [ "$(git -C "$SRC" rev-parse HEAD)" != "$expected" ]; then
    echo "error: $tag is at $(git -C "$SRC" rev-parse HEAD), not $expected as scripts/bb-commits records" >&2
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

# sha256sum on Linux, shasum on macOS: the same line format.
bb_sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

# Records the sha256 of a built prover library in scripts/bb-libs.sha256 (one line per path, relative to the root),
# which the app builds check before shipping it.
bb_record_hash() {
  local root=$1 path=$2 list="$1/scripts/bb-libs.sha256"
  touch "$list"
  grep -v "  $path\$" "$list" > "$list.tmp" || true
  (cd "$root" && bb_sha256 "$path") >> "$list.tmp"
  sort -k2 "$list.tmp" > "$list" && rm "$list.tmp"
}

# Fails unless each path matches its line in scripts/bb-libs.sha256.
bb_check_hashes() {
  local root=$1 path
  shift
  for path in "$@"; do
    grep -q "  $path\$" "$root/scripts/bb-libs.sha256" || { echo "error: no hash recorded for $path in scripts/bb-libs.sha256: rebuild it" >&2; return 1; }
    (cd "$root" && grep "  $path\$" scripts/bb-libs.sha256 | bb_sha256 --check --quiet) \
      || { echo "error: $path is not the library scripts/bb-libs.sha256 records: rebuild it" >&2; return 1; }
  done
}
