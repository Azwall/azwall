<!-- Working notes of one area of the 2026-10-28 store-release audit. The consolidated, verified report is ../2026-10-28-store-release-audit.md; where the two differ, it prevails. -->

# Azwall pre-release audit: Rust core, prover, build pipeline, supply chain

Date: 2026-10-28. Scope: `rust/azwall-core`, `rust/azwall-prover`, `rust/azwall-ffi`, `scripts/`, `pxe/` build
and dependencies, `docs/release.md`. Read-only review of the working tree at commit `ef3ccb5` (clean).
Mnemonic derivation (`mnemonic.rs`) was reviewed separately and is out of scope here.

Ratings: P1 keys/funds at risk, prover RCE, supply-chain substitution of the prover; P2 wallet DoS, key exposure to
the app's own users, a build that could ship secrets; P3 hardening. CONFIRMED = reproduced from the code or a
command; PLAUSIBLE = reasoned from the code, not reproduced.

## Results of the checks that were run

| Check | Result |
|---|---|
| `cargo test --workspace` | 73 passed, 0 failed, 1 ignored (`rpc_live`) |
| `cargo clippy --workspace --all-targets -- -D warnings` | clean |
| `scripts/check-limits.py`, `scripts/check-swallowed.py` | both pass |
| `cargo audit` (installed during the audit, 1381 advisories) | **2 vulnerabilities** (F3), 1 unmaintained warning (F4) |
| `cargo tree -d` | no duplicate crypto crate; `getrandom 0.2` only under `ring`/`rustls`, `getrandom 0.4` for the mnemonic |
| `pnpm audit --prod` (pxe/) | 8 advisories (6 high, 2 moderate), none reachable from the web bundle (F11) |
| `readelf` on `libbb_v6.0.0.so`, `libazwall_ffi.so` (arm64-v8a) | PIE, GNU_RELRO + BIND_NOW (full RELRO), NX stack, LOAD align 0x4000 (16 KB), exports exactly `azbb_api`/`azbb_free`, `__stack_chk_fail` present in libbb |

## Findings

### F1. P2 CONFIRMED: the iOS build has no `--public-only` gate, so an archive can ship `.env` RPC keys

`ios/project.yml:64-70` ("Resolve network config") runs
`cargo run -p azwall-core --bin azwall-config -- --out assets/azwall-config.json` for every configuration, Release
and Archive included, and nothing on the iOS side runs `azwall-config --public-only`. Android has the gate
(`android/app/build.gradle.kts:144-167`, hooked to `preReleaseBuild`/`preStagingBuild`), iOS has only the CRS and
Rust-core freshness checks. `docs/release.md` documents Android only.

Scenario: a developer with `AZWALL_RPC_MAINNET_V6=https://lb.drpc.org/aztec-mainnet/<KEY>` in `.env` archives the
iOS app; `azwall-config.json` inside the IPA carries the keyed URL (`config.rs:39-54` puts `rpcUrl` and `l1.rpcUrls`
in the PXE open params by design) and anyone who downloads the IPA reads the key. This is exactly what the Android
gate prevents.

Fix: in the iOS "Resolve network config" phase, run `azwall-config --public-only` when `$CONFIGURATION` is Release
(the binary already supports it, `azwall-config.rs:403-406`), and document the iOS release in `docs/release.md`.

### F2. P2 CONFIRMED: the native prover's sources are pinned by a mutable git tag, with no commit or output hash

`scripts/lib/bb-sources.sh:8-21` clones `aztec-packages` at `--branch v$release` and only verifies that HEAD carries
the tag (`git tag --points-at HEAD | grep -qx "$tag"`). Nothing in the repository records which commit `v6.0.0`
must be (today: `0219f11d8133970df3974fe5ae65e006bf8c193a`, which also carries `v6.0.0-rc.7` and a nightly tag), nor a
hash of the resulting `libbb_v6.0.0.so` / `AzBB_6_0_0.xcframework`. A moved tag (force-push, compromised upstream
account, or a GitHub-side incident) silently changes the prover that signs every transaction's proof, and a
third party cannot reproduce or verify the shipped prover from the repository alone.

Compounding, inside the checkout: `barretenberg/cpp/cmake/libdeflate.cmake:16` fetches libdeflate at `GIT_TAG v1.24`
(no hash), and `bb-sources.sh:27-28` executes upstream's `protocol/constants-codegen/scripts/generate.sh` with Node at
build time. The `barretenberg-rs` crate that defines the msgpack schema is pinned `=6.0.0` with a checksum in
`Cargo.lock` (good), but its crates.io publisher could not be verified offline (its manifest only says the repository
is `AztecProtocol/aztec-packages`).

Fix: record the expected commit per release in `bb-sources.sh` (e.g. `BB_COMMITS[6.0.0]=0219f11d…`) and fail when
`git rev-parse HEAD` differs; record the sha256 of each built library (or at least print and keep it in the release
notes) and have `build-android.sh`/`build-ios.sh` verify it before staging; confirm the crates.io owner of
`barretenberg-rs` once.

### F3. P3 CONFIRMED: two RustSec advisories in the TLS stack of the node probe

`cargo audit`: RUSTSEC-2026-0382 (`rustls-webpki 0.103.15`, "certificate verification may accept distrusted
intermediates") and RUSTSEC-2026-0381 (`webpki-roots 1.0.9`, "bundled root certificates allow trust in distrusted
emSign intermediates"). `cargo update -p rustls-webpki -p webpki-roots@1.0.9 --dry-run` resolves to 0.103.16 and
1.0.11, so the fix is a lockfile bump.

Reachability: only `check_node` (`rpc.rs:102-105`, one `node_getNodeInfo` POST) uses this stack; the PXE talks to the
node from the WebView. A MITM holding a certificate chained to one of the distrusted intermediates could answer the
probe (claim compatibility) and would receive the request URL, i.e. the API key if `.env` set one. Low likelihood,
trivial fix: bump before release.

### F4. P3 CONFIRMED: the shipped FFI library pulls the bindings generator into its dependency graph

`rust/azwall-ffi/Cargo.toml` declares `uniffi = { version = "0.32", features = ["cli"] }` under `[dependencies]`, so
`uniffi_bindgen`, `cargo_metadata`, `askama` (+ `basic-toml`, RUSTSEC-2026-0374 unmaintained), `tempfile` and friends
are part of the normal dependency graph of the cdylib/staticlib (`cargo tree -e normal -p azwall-ffi`: 177 crates).
LTO drops the dead code, but every build script and proc-macro of those crates runs when the store library is built,
which widens the supply-chain surface for no runtime benefit.

Fix: `[features] bindgen = ["uniffi/cli"]`, `required-features = ["bindgen"]` on the `uniffi-bindgen` bin, and
`cargo run --features bindgen` in `scripts/gen-bindings.sh`.

### F5. P3 PLAUSIBLE: the HTTP client follows redirects and accepts `http://`

`rpc.rs:23` builds the agent with the ureq defaults: `https_only = false`, `redirects = 5` (ureq `agent.rs:260-262`).
On a 301/302/303 the POST becomes a GET to the attacker-chosen `Location`, possibly `http://` (ureq `unit.rs:189-200`);
307/308 for a POST are not followed. The API key sits in the original URL path and is not resent unless the
`Location` is relative on the same host, so the leak is negligible, but there is no reason to follow anything.
Separately, `network.rs:235-238` (`is_http_url`) accepts `http://` for `defaultRpc`, `l1.rpc`, `l1.fallbackRpc` and
the `.env` URLs, while dApps and explorers must be https; a registry edit could ship a plaintext node endpoint.

Fix: `AgentBuilder::new().https_only(true).redirects(0)`, and require https in the registry except for localhost.

### F6. P3 PLAUSIBLE: node-controlled text reaches the UI and the diagnostic log unbounded; JSON-RPC id unchecked

`rpc.rs:96-97` surfaces the node's `error.message` verbatim (`AzwallError::Node { detail }` → screens/log), and
`rpc.rs:133` puts the node's `nodeVersion` string in a mismatch line; both can be up to the 10 MiB body limit and
contain anything (phishing text, control characters). `rpc.rs:91-99` sends `id: 1` and never checks the reply's `id`
or `jsonrpc`. The transport-level redaction (`redact_urls`, `rpc.rs:45-70`) is correct and tested.

Fix: cap and sanitise both strings (e.g. 200 chars, printable only) and check `id == 1`.

### F7. P3: recovery phrase and signing key cross the FFI as `String` without zeroisation

`azwall-ffi/src/lib.rs:189-205`: `new_recovery_phrase`, `normalize_recovery_phrase(phrase)`,
`account_signing_key(phrase, index)` take and return `String`. uniffi copies them into a `RustBuffer` that is freed,
not wiped, and the Kotlin/Swift side receives an immutable JVM/Swift string the app cannot zero. Realistic exposure
is a memory dump of the running process (root, debugger, a crash dump never sent anywhere), so this is hygiene rather
than a reachable leak. Fix on the Rust side with `zeroize` (`Zeroizing<String>` for intermediates, wipe before the
`RustBuffer` is freed is not possible with stock uniffi); on the app side prefer `ByteArray`/`[UInt8]` parameters the
caller can zero. No exported function panics on bad input: config, network id, request bytes and thread counts all
return errors, and uniffi wraps every call in `catch_unwind` (`uniffi_core/src/ffi/rustcalls.rs:207`), so a panic
becomes an `InternalException`, never an abort across the FFI.

### F8. P3 PLAUSIBLE: the request cap is above a phone's memory

`wire.rs:158-163`: 256 MiB per inflated field and 1 GiB for the whole request (commit 63fc58b, verified: the sum is
checked per step at `wire.rs:93-97`, lengths use `checked_add` at `wire.rs:47`, steps are capped at 64, gunzip is
bounded with `take(cap + 1)` at `wire.rs:171-177`). The cap keeps the arithmetic honest but a request that inflates to
~1 GiB still gets the app killed by the low-memory killer before the cap triggers. Only the bundled PXE page can send
one: the `prove` handler exists only on the PXE host's channel (`AndroidPxeLauncher.kt:33-50`), the page is served
from the APK by `WebViewAssetLoader` with `allowFileAccess = false` and navigation refused (`PxeHost.kt:84-101`), and
the dApp browser's bridge is a different listener. Consider ~256 MiB for the whole request; real requests are tens of
MiB.

### F9. P3 observation: the C++ deserialisers are the real trust boundary, and are not fuzzed here

The bytes from the PXE page end up in Barretenberg's ACIR/witness/VK deserialisers (`ChonkLoad`/`ChonkAccumulate`,
`bindings/v6.rs:175-185`). A memory-safety bug there would run code in the app process with a compromised PXE page as
the only prerequisite. Mitigations in place and verified: `c_bind.cpp:48-60` catches `std::exception` and `...` into
an `ErrorResponse` (exceptions are only disabled for WASM, `cmake/arch.cmake:1-6`); the library is PIE with full
RELRO, NX stack, stack protector, loaded `RTLD_LOCAL` with two exports; `BB_LITE=ON`, `AVM=OFF` cut the server-side
code. Nothing to change before release; a fuzzing pass of `bbapi` with the AZS2 corpus would be the next step.

### F10. P3 nits in the CRS path

- `srs.rs:139-146` accepts a file larger than expected (`bytes.len() < min_len` only). An oversized `g1_compressed.dat`
  is refused by Barretenberg's own check (`bbapi_srs.cpp:27-31`, bytes per point must be 32 or 64) rather than used,
  so it is harmless; checking `==` would give a clearer error.
- `scripts/fetch-crs.sh:20-22` moves a downloaded file into `assets/crs/` before the sha256 check at lines 31-35, so a
  bad download stays in place after the script fails. Harmless because both app builds re-run the check
  (`build.gradle.kts:106-114`, `project.yml:59-62`) and Barretenberg verifies in-binary SHA-256 chunk hashes and the
  canonical first points at init (`bbapi_srs.cpp:44-74`).

### F11. P3 CONFIRMED not reachable: the 8 pnpm advisories are node-only transitive dependencies

`pnpm audit --prod` (429 dependencies):

| Severity | Package | Path (abridged) | Patched |
|---|---|---|---|
| high ×5 | `systeminformation` (GHSA-wphj-fx3q-84ch, -9c88-49p5-5ggf, -hvx9-hwr7-wjj9, -5xpp-75jx-m839, -5vv4-hvf7-2h46) | `@aztec-labs/pxe > simulator > telemetry-client > @opentelemetry/host-metrics` | >=5.31.7 |
| high | `@opentelemetry/propagator-jaeger` (GHSA-45rx-2jwx-cxfr) | `telemetry-client > @opentelemetry/sdk-trace-node` | none |
| moderate | `@opentelemetry/core` baggage (GHSA-8988-4f7v-96qf) | `telemetry-client > sdk-logs/sdk-metrics` | >=2.8.0 |
| moderate | `uuid` v3/v5/v6 buffer (GHSA-w5hq-g745-h8pq) | `@aztec-labs/stdlib > @google-cloud/storage > gaxios` | >=11.1.1 |

None of `systeminformation`, `host-metrics`, `JaegerPropagator`, `propagator-jaeger`, `baggage`, `gaxios`,
`google-cloud` appears in the built bundle (`grep -l` over `assets/pxe/v6/assets/*.js`: 0 files each): they are
Node-side telemetry and storage code that Vite tree-shakes out, and the WebView never parses incoming HTTP headers.
For hygiene, `pnpm.overrides` for `uuid` and `@opentelemetry/core` would silence the two that have a fix, as was done
for zod (`pnpm-workspace.yaml`).

### F12. P3: release documentation gaps

`docs/release.md` covers Google Play only. Missing: the iOS procedure (and its `.env` gate, F1); a step to verify the
Barretenberg sources commit and the libraries' hashes (F2); `cargo audit` / `pnpm audit` before tagging; a line that
debug and `installStaging` builds embed whatever `.env` holds (the staging build does get the public-only gate,
`build.gradle.kts:165-167`). Signing and version bumps are covered and enforced (`tests/versions.rs`,
`findByName("upload")` makes an unsigned bundle, which Play refuses, rather than a debug-signed one).

### F13. P3: the Android release gate is wired with `findByName` and would vanish silently

`build.gradle.kts:165-167`: `listOf("preReleaseBuild", "preStagingBuild").forEach { tasks.findByName(it)?.dependsOn(…) }`
inside `afterEvaluate`. If a future AGP renames or restructures those tasks, the release-core, 16 KB and public-only
checks stop running without any error. Use `tasks.named(name)` (fails loudly) or assert both were found.

## Strong points (verified)

- **HTTP client**: `ureq 2.12.1` with `tls` = rustls 0.23 + bundled Mozilla roots (`webpki-roots`), not the device
  store, certificate validation on; `timeout(20 s)` is the overall budget including body (ureq `agent.rs:471-476`);
  `into_string()` caps bodies at 10 MiB (ureq `response.rs:33,456-466`); transport errors are URL-redacted to
  scheme + host (`rpc.rs:40-41, 45-70`, tested at `rpc.rs:214-220`). `check_node` only compares what the node says
  with the registry; the PXE separately enforces `vkTreeRoot`/`protocolContractsHash` passed in `pxe_open_params`.
- **Config**: strict `.env` parser (`dotenv.rs:263-296`: keys `[A-Za-z0-9_]`, quotes must close, nothing after a
  closing quote, `#` comment rules tested); non-http(s) values rejected for RPC and L1 (`config.rs:63-64, 85-90`);
  `--public-only` covers both `AZWALL_RPC_*` and `AZWALL_L1_RPC_*` and prints hosts only (`config.rs:105-119`,
  `azwall-config.rs:403-406`); the Android gate also refuses a debug Rust core and misaligned libraries. The Fee Juice
  portal and `portalFromBlock` come from the registry only (`network.rs:83-86`), a node cannot redirect deposits.
  The generated `assets/azwall-config.json` today holds only the public dRPC/Tenderly/0xrpc/MEV Blocker hosts.
- **dlopen path**: the registry's `barretenberg` string reaches `library::path_in` only after
  `bindings::binding_for(release)` matched the compiled table (`manager.rs:199-202`, `bindings/mod.rs:27-34`,
  `"6.0.0"` only), so `..`/`/` in `networks.json` cannot select a file; the directory is the app-private
  `nativeLibraryDir` (extracted at install, `useLegacyPackaging = true`) or `privateFrameworksPath` (code-signed);
  `libloading 0.8.9` opens with `RTLD_LAZY | RTLD_LOCAL`; the library itself is `BIND_NOW`.
- **Prover lifecycle**: `ProverManager::prove` holds the mutex for the whole proof (`manager.rs:225-229`), so
  `unload`/`activate` cannot pull the library from under a running proof; `Drop` closes the channel and joins
  (`engine.rs:342-350`); a panic on the prover thread surfaces as `Thread("prover thread died…")`, a poisoned mutex is
  reused deliberately (`manager.rs:247-250`); `azbb_free` releases every response with the library's allocator
  (`library.rs:53-66`).
- **Shim and link flags** (`build-bb-shared.sh:59-73`, `build-bb-ios.sh:94-112`): version script / exported-symbols
  list limiting exports to `azbb_api`/`azbb_free` (confirmed with `readelf`), `-fvisibility=hidden`, `-g0`,
  `--exclude-libs,ALL`, `-z noexecstack`, `-s`, `max-page-size=16384`; NDK clang defaults supply `-z now`, `-z relro`
  and `-fstack-protector-strong` (confirmed on the binary).
- **Supply chain, Rust**: `Cargo.lock` v4 committed with checksums; `barretenberg-rs = "=6.0.0"` with `ffi` off, so its
  `build.rs` download path (`curl` from GitHub releases) is never compiled; `flate2` on the pure-Rust backend;
  `strip = "symbols"`, `lto = "thin"` for release.
- **Supply chain, PXE**: `pnpm-lock.yaml` v9 with an `integrity` on all 606 packages, `--frozen-lockfile` in both app
  builds, every `@aztec-*` dependency pinned exactly and cross-checked with `networks.json` by
  `tests/registry_file.rs:75-90`; dependency lifecycle scripts blocked except `esbuild`
  (`pnpm-workspace.yaml` `allowBuilds`, confirmed in `.modules.yaml`); `pxe/v6/src` holds only `version.ts` in git,
  the rest is regenerated by `sync-host.mjs` before every build; no sourcemaps in `assets/pxe/v6` (65 MB);
  `__AZWALL_BUILD__` is a version+commit string. Randomness in the page is WebCrypto: bb.js `randomBytes` uses
  `crypto.getRandomValues`, `@aztec-labs/foundation` delegates to it, `native_store.ts:25` uses `getRandomValues` for
  IVs; the 44 `Math.random` hits in the bundle are Aztec `static random()` test fixtures, SQLite WASM's non-crypto
  `xRandomness`, and viem/ox JSON-RPC request ids.

## Recommended order before the store builds

1. F1: add `--public-only` to the iOS Release build phase; document iOS in `docs/release.md`.
2. F3: `cargo update -p rustls-webpki -p webpki-roots@1.0.9`, re-run `cargo audit`.
3. F2: pin the Barretenberg commit in `bb-sources.sh`, record the library hashes.
4. F4, F5, F6, F13: small hardening changes, each a few lines.
