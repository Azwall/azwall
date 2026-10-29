<!-- Working notes of one area of the 2026-10-29 re-audit. The consolidated, verified report is ../2026-10-29-store-release-reaudit.md; where the two differ, it prevails. -->

# Re-audit: Rust core, build pipeline, supply chain (F10, F11, F17, Rust/build P3) — 2026-10-29

Revision reviewed: `01267c0` (clean tree). Baseline: `ef3ccb5`. Commits reviewed: `341eac5`, `cde2154`, `b38d2f6`,
`5c08645`, `b0eb710` (its `ios/project.yml` part), `01267c0`. Read-only; scratch work under the session scratchpad.

## Checks run on this revision

| Check | Result |
|---|---|
| `cargo audit` | 0 vulnerabilities; 1 allowed warning (`basic-toml` unmaintained, RUSTSEC-2026-0374: in `Cargo.lock` for the optional `bindgen` feature, absent from the shipped library's graph). No `audit.toml` ignores anything. |
| `cargo tree -e normal -p azwall-ffi` | 236 lines, 122 unique crates (146 with `--features bindgen`); `uniffi_bindgen` and `basic-toml`: 0 hits without the feature |
| `cargo build -p azwall-ffi --features bindgen --bin uniffi-bindgen` | builds; without the feature cargo refuses: "target `uniffi-bindgen` … requires the features: `bindgen`" |
| `cargo test --workspace` | 73 passed, 0 failed, 1 ignored (`rpc_live`) |
| `cargo clippy --workspace --all-targets -- -D warnings` | clean |
| `scripts/check-limits.py`, `scripts/check-swallowed.py` | both pass |
| `Cargo.lock` | `rustls-webpki 0.103.16`, `webpki-roots 1.0.11` (the `0.26.11` entry is the shim over 1.0.x), `rustls 0.23.46`, `ureq 2.12.1` |
| Local prover evidence | `vendor/aztec-packages-v6.0.0` HEAD = `0219f11d…` (tags `v6.0.0`, `v6.0.0-rc.7`, nightly), tracked tree clean; `vendor/bb-shared/arm64-v8a/libbb_v6.0.0.so` = record; all 12 copies under `android/*/build/intermediates` (merged, stripped, debug/staging/release) are byte-identical to the record, so AGP's strip is a no-op on the `-Wl,-s` library; a relink of `vendor/bb-static/6.0.0/arm64-v8a` with NDK 27.3.13750724 into the scratchpad reproduces `c1f07d48…` |
| `sha256sum`/`shasum --check` on empty input and on a missing file | both exit 1 (the Xcode phase fails safely on a record without iOS lines, or a missing framework) |
| `is_http_url` copied to a scratch binary | see N1 |

## Verdicts

### F17 — P3 — RustSec advisories in the node probe's TLS stack: CLOSED

`Cargo.lock` holds `rustls-webpki 0.103.16` and `webpki-roots 1.0.11`; `cargo audit` reports no vulnerability.

### P3 — `uniffi` `cli` feature in the shipped library: CLOSED

`rust/azwall-ffi/Cargo.toml:17-28`: `required-features = ["bindgen"]` on the bin, `bindgen = ["uniffi/cli"]`,
`uniffi = "0.32"` without features. `scripts/gen-bindings.sh:14` runs `cargo run … --features bindgen --bin
uniffi-bindgen`. Measured: generator and `basic-toml` out of the normal graph, 24 unique crates fewer (the manifest
comment's "~180 crates of generator" overstates it; harmless).

### P3 — `ureq` redirects and `http://` endpoints: PARTIALLY CLOSED

- `rust/azwall-core/src/rpc.rs:25`: `redirects(0)` — closed. `https_only(true)` is not set (it would refuse the
  localhost exception); the URL is validated upstream instead. The transport's only caller is `check_node`
  (`rust/azwall-ffi/src/lib.rs:147-149`) with `network.rpc_url` from the resolved config, i.e. a registry or `.env`
  URL that passed `is_http_url` (`config.rs:63,85`; `network.rs:205,215,222,227`). Neither app takes a URL from the
  user (the only Settings text field renames an account, `SettingsScreen.kt:107`, `SettingsView.swift:61`), so no
  unvalidated URL reaches the agent today. Defence in depth would be an `https_only` agent when the host is not local.
- **N1 — P3 — CONFIRMED (scratch copy of the function): `is_http_url` accepts plain http to any host behind a
  port-like userinfo.** `network.rs:240-250` takes the host as the text before the first `:` of the authority. So
  `http://localhost:1@node.example/`, `http://127.0.0.1:x@node.example` and `http://[::1]:8080@node.example/` return
  `true`, while a URL parser (ureq 2's `url` crate; Python's `urlsplit` gives host `node.example`) sends the request to
  `node.example` in clear. The test at `network.rs:373` (`http://user@node.example`) passes for the wrong reason (no
  `:` in the userinfo). The input is `networks.json` or `.env`, developer-controlled, so this weakens a lint rather than
  opening a path; a store build's registry is https. Fix: refuse any `@` in the authority (a node URL carries no
  credentials) before extracting the host; add the three cases to `http_urls`. `http://LOCALHOST` and `http://127.1`
  are refused (safe direction).

### F10 — P2 — iOS archive can ship `.env` RPC keys: PARTIALLY CLOSED

- `ios/project.yml:72-76`: `[ "$CONFIGURATION" = Release ] && public=--public-only`. `project.yml` defines no
  `configs:`/`schemes:`, so XcodeGen generates Debug and Release only and the scheme archives with Release: an Archive
  runs the gate. A configuration added later (e.g. "Staging") would skip it silently (single-string compare).
- **N2 — P2 — CONFIRMED by the phase's shell semantics and the binary's ordering (not exercised on a Mac): the gate's
  refusal cannot fail the archive.** The "Resolve network config" phase (`project.yml:68-80`) has no `set -e` and its
  last line is `cd "$SRCROOT/../pxe" && pnpm install --frozen-lockfile --silent && pnpm build`; a run-script phase's
  status is its last command's. `azwall-config --public-only` refuses before writing (`azwall-config.rs:46`, write at
  `:58`), so `assets/azwall-config.json` left by the developer's previous Debug build — holding the keyed URL — stays on
  disk, `pnpm build` succeeds, the phase passes and the archive bundles the stale file (`project.yml:20-21` adds it as a
  resource). This is the exact F10 scenario. The other phases use `|| { …; exit 1; }` (`project.yml:62,67`) or `exit 1`
  (`:54-57`); only this one does not. Fix: `set -euo pipefail` at the top of the phase (or `|| exit 1` on the cargo line)
  and remove `assets/azwall-config.json` before running the generator so a refusal can never leave a stale file.
  PLAUSIBLE aggravation: the phase declares `outputFiles` and no `inputFiles`; with XcodeGen's default "based on
  dependency analysis", Xcode may skip a phase whose outputs exist, in which case the gate does not run at all on an
  archive made after a debug build. Verify on the Mac: archive with a keyed `.env`, inspect the IPA's
  `azwall-config.json`.
- `docs/release.md:83-96` documents the iOS release (closes that part of the docs gap).

### F11 — P2 — Barretenberg pinned by a mutable tag, no library hash: PARTIALLY CLOSED

Closed:
- Commit pin. `scripts/bb-commits` records `6.0.0 0219f11d8133970df3974fe5ae65e006bf8c193a`.
  `scripts/lib/bb-sources.sh:24-33` compares the full `git rev-parse HEAD` with it, after the tag check and **before
  any cmake step** (`build-bb-android.sh:35` then `:41`; `build-bb-ios.sh:27` then `:77`). An already-present checkout
  goes through the same lines (18-33 run whether or not the clone happened), and a release without a record line fails
  (`:26-29`).
- Library hashes. `scripts/bb-libs.sha256` has three lines: the Android `arm64-v8a` `.so`, the iOS device and simulator
  Mach-O. Android: `scripts/build-android.sh:36` runs `bb_check_hashes` before the `cp` into `jniLibs`, with a presence
  check (`bb-sources.sh:71`) and the sha check (`:72`). The recorded hash is the hash of the bytes shipped: the library
  is linked with `-Wl,-s` (`build-bb-shared.sh:34`) and AGP's `stripReleaseDebugSymbols` leaves it byte-identical (all
  intermediates = record; `useLegacyPackaging = true` stores it as-is). iOS: `project.yml:63-67` "Check the prover" runs
  `shasum --check` on every `vendor/bb-ios/` line at every build (no `outputFiles`, so never skipped); the file hashed is
  the Mach-O `…/ios-arm64/AzBB_6_0_0.framework/AzBB_6_0_0` (`build-bb-ios.sh:84-86`), not a directory; empty input and a
  missing file both fail. Code signing changes the embedded copy later, as expected on iOS.
- The link step is reproducible (relink with the same NDK gives the recorded hash).

Not closed:
- **N3 — P3 — CONFIRMED: the pin covers HEAD, not the tree.** `bb-sources.sh` never checks that the checkout is clean
  (`git status --porcelain --untracked-files=no` or `git diff --quiet HEAD`). A checkout at the recorded commit with a
  locally edited source (or an untracked header shadowing one) builds, is recorded by `bb_record_hash` and ships. Fix:
  fail when tracked files differ from HEAD (the generated `aztec_constants.hpp` and the build directories are untracked,
  so exclude untracked files).
- libdeflate is still fetched by tag inside the checkout: `barretenberg/cpp/cmake/libdeflate.cmake:15-16`
  (`GIT_TAG v1.24`, no hash), the compounding point of the original finding, untouched. Fix: after configure, compare
  `_deps/libdeflate-src` HEAD with a recorded commit, or point `FETCHCONTENT_SOURCE_DIR_LIBDEFLATE` at a pinned checkout.
- **Consistency, not provenance** (P3, should be said in `docs/release.md`). `bb_record_hash` runs automatically at the
  end of every `build-bb-android.sh` (`:52`) and `build-bb-ios.sh` (`:84-86`): the record is "what the last build
  produced", so whoever can change the library can rewrite the record in the same commit. `docs/release.md:12-14` says
  "the app builds refuse to ship a library that does not match its line" and "to check a shipped prover, rebuild it from
  the recorded commit and compare"; the second holds only if the cmake build is reproducible, which was not tested here
  (10 min, writes into `vendor/`). The iOS procedure (`release.md:87`) runs `scripts/build-bb-ios.sh all` at every
  release, which re-records the hashes: on the release Mac the check is tautological. The value today: a `vendor/` copy
  that differs from the maintainer's last build is refused, and each shipped build is tied in git history to a commit and
  a hash. Suggest saying that, recording the NDK/Xcode versions next to the hash, and treating reproducibility as the open
  step.
- **Gradle never checks the record** (P3, CONFIRMED). `android/app/build.gradle.kts` has no `libbb`/`bb-libs` mention;
  `checkReleaseInputs` (`:144-164`) checks the Rust core's profile and the 16 KB alignment of whatever `.so` files are
  in `jniLibs`, not that a `libbb_v<release>.so` exists for each release of `networks.json` nor that it matches
  `scripts/bb-libs.sha256`. Consequences: (a) after `build-bb-android.sh` re-records, a stale `jniLibs` copy ships with a
  record saying otherwise; (b) `build-android.sh:31` removes the `jniLibs` prover **before** the check at `:36`, so a failed
  hash check (the script exits under `set -e`) leaves `jniLibs` without a prover and `bundleRelease` still succeeds (the
  app fails at proof time with "a missing library"); pre-existing for a missing vendor file (`:34`). Fix: in
  `checkReleaseInputs`, for each release in `networks.json` require `jniLibs/arm64-v8a/libbb_v<r>.so` and compare its
  sha256 with the `vendor/bb-shared/arm64-v8a/` line.
- **iOS partial record passes** (P3, CONFIRMED by reading). `project.yml:67` checks the lines present; it does not
  assert that the embedded device slice has one. Delete the `ios-arm64` line and the build passes on the simulator line
  alone. Fix: grep the specific device-slice path and fail when absent, as `bb_check_hashes` does on Android.

### Rust/build P3 items of "Other findings"

| Item | Verdict | Evidence |
|---|---|---|
| iOS deployment target for the Rust core's C/asm objects | CLOSED | `scripts/build-ios.sh:17` exports `IPHONEOS_DEPLOYMENT_TARGET=17.0`, matching `project.yml:7` |
| Release gate wired with `findByName` (F13 of the notes) | NOT CLOSED | `build.gradle.kts:166-167` unchanged |
| Node-controlled `error.message`/`nodeVersion` unbounded; JSON-RPC `id` unchecked | NOT CLOSED | `rpc.rs:93-99,133` unchanged |
| Phrase/key zeroisation across the FFI; 1 GiB request cap; `fetch-crs.sh` ordering; `srs.rs` size check | NOT CLOSED | unchanged (hygiene) |
| `docs/release.md` gaps | PARTIALLY CLOSED | iOS procedure and provenance section added; no `cargo audit`/`pnpm audit` step; `scripts/test-all.sh:11-12` runs the two check scripts, not clippy nor audit |
| `pnpm` overrides | not re-checked | untouched by the commits |

## Store listing and Data safety proposal (`store/play/README.md:91-105` vs `docs/privacy.md` and the flows)

Main point is right and conservative: addresses and transactions declared as "Financial info › Other financial info",
Collected **Yes**, Shared **Yes**, to dRPC and the three Ethereum endpoints; nothing claims "no data shared". "Web
browsing" No/No for pages the user opens is defensible (Google scopes out a WebView navigating the open web). The host
inventory of the sources (`duckduckgo`/`startpage`/`brave` as user-picked search, `aztec.network` and `aztec.palmyr.io`
start-page links, `aztecscan` on tap, `azwall.github.io` privacy link, `drpc`, `tenderly`, `mevblocker`, `0xrpc`) shows
no other recipient, so "talks only to the Aztec node and Ethereum endpoints it needs, and to the sites you open" holds.
"https only; plain http is refused except to the device itself" holds for a store build: Android release
`cleartextTrafficPermitted="false"` (`network_security_config.xml:4`; debug adds localhost/127.0.0.1), iOS ATS with
`NSAllowsLocalNetworking` only (`project.yml:93-94`), Rust `is_http_url` for the registry (N1 does not change this: the
registry is https).

- **S1 — CONFIRMED — a false claim in the privacy policy.** `docs/privacy.md:48`: "You can replace the default Aztec
  node and Ethereum endpoint with your own." No such setting exists in either app; the only override is `.env` at build
  time, which the store builds refuse. Remove it or reword ("when you build the app yourself").
- **S2 — PLAUSIBLE — Google's taxonomy.** An account address is an identifier of the user's account; "Personal info ›
  User IDs" ("an account ID, account number") may be expected alongside "Other financial info". Over-declaring is safe,
  under-declaring is what gets a listing rejected: add User IDs with the same answers.
- **S3 — wording.** The policy's Aztec-node row (`privacy.md:43`) says "Encrypted chain data requests and your
  transactions" and omits the account addresses, which the Data safety row and `release.md:75` do name. Align the policy.
- **Deletion question.** "The developer holds none…" is honest, but Google's question is whether the app offers a
  deletion request; what dRPC/Tenderly log cannot be deleted by the developer. Say so, to keep the form consistent.
- "encrypted by the phone's secure hardware" (listing and `privacy.md:24`): hardware-backed on virtually every API 28+
  device, not guaranteed; "where the phone has it" would be exact. Low risk.

## Regressions and robustness of the changed scripts

- N2 is the regression-class issue: the new gate's failure cannot fail the Xcode phase.
- Fresh machine: `build-android.sh` needs a record line for each ABI it stages; only `arm64-v8a` is recorded, so
  `scripts/build-android.sh x86_64` (emulator) fails with "no hash recorded … rebuild it" until
  `build-bb-android.sh 6.0.0 x86_64` has run (which records it). Loud, not silent; the fresh build then dirties
  `bb-libs.sha256` with a new line (and a different arm64 hash if the toolchain differs).
- `|| true` at `bb-sources.sh:61` only absorbs grep's "no line left" status; `touch` creates the record; all callers run
  under `set -euo pipefail` and the library functions `return 1`. No check is wired through a task name that may not
  exist, except the pre-existing `findByName` gate.
- `bb_record_hash`/`bb_check_hashes` use the path as an unescaped regex (`.` is a wildcard); harmless for these names.
- `b38d2f6`'s message says "the Xcode build refuses one that differs", but the phase was added in `b0eb710`
  (`project.yml:63-67`); same effect, misleading history only.

## Summary

CLOSED: F17; uniffi/bindgen feature-gate; iOS deployment target. PARTIALLY CLOSED: F10 (N2, P2: the Xcode phase cannot
fail on the gate's refusal and leaves a stale keyed config), F11 (HEAD-only pin N3, libdeflate by tag, Gradle checks
nothing, iOS partial record passes, record is consistency not provenance), ureq/http (N1: `user:port@host` bypass of
the localhost check, P3), release docs (no audit step). NOT CLOSED: `findByName` gate, node-string bounds and RPC id,
zeroisation, 1 GiB cap, CRS nits. Store: S1 false sentence in `privacy.md:48`; the Data safety proposal otherwise
matches the flows; consider adding "User IDs".
