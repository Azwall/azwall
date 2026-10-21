---
title: Architecture
nav_order: 3
---

# Architecture

```
 ┌──────────── Android (Kotlin/Compose) ────────────┐   ┌────────────── iOS (Swift/SwiftUI) ──────────────┐
 │ app: WalletViewModel, NetworkScreen              │   │ Azwall: NetworkView                             │
 │ core: WalletBackend ← RustWalletBackend          │   │ AzwallKit: WalletModel, WalletBackend           │
 │       AzwallRuntime (assets, CRS, lib dir)       │   │ AzwallRust: RustWalletBackend, AzwallRuntime    │
 └───────────────────────┬──────────────────────────┘   └───────────────────────┬─────────────────────────┘
                         │ JNA                                                  │ C ABI (XCFramework)
                         └──────────────────────┬───────────────────────────────┘
                                   rust/azwall-ffi (uniffi: WalletCore)
                         ┌──────────────────────┴───────────────────────┐
                 rust/azwall-core                               rust/azwall-prover
       registry · .env · node JSON-RPC · compat        engine thread · release manager · bindings
                                                                  │ dlopen (RTLD_LOCAL)
                                               libbb_v5.1.0.so   libbb_v6.0.0-rc.6.so   …
```

Both apps depend on a `WalletBackend` interface, never on the generated bindings directly, so
their view logic is tested with fakes (JVM tests on Android, `swift test` on iOS).

## Several Aztec versions

Aztec runs incompatible versions at once (today testnet v5 and v6). Each network in
[`networks.json`](../networks.json) pins:

| Field | Used for |
|---|---|
| `aztec` | The PXE bundle version; its major must match the node's (`node_getNodeInfo`) |
| `barretenberg` | The native prover release (Chonk format must match the circuits) |
| `expected` | L1 chain id, rollup version (checked against the node), VK tree root, protocol contracts hash |
| `defaultRpc` | Public endpoint, overridden by `AZWALL_RPC_<ID>` in `.env` |
| `tokens` | Optional: tokens every wallet follows from the start (`address`, `name`, `symbol`, `decimals`); a removed one stays removed |
| `dapps` | Optional: sites the browser start page suggests on this network (`name`, https `url`, `description`) |

### Native prover

- Each Barretenberg release is a self-contained shared library exporting only `azbb_api` and
  `azbb_free` (`scripts/build-bb-shared.sh`), loaded with `dlopen`/`RTLD_LOCAL`. The prototype
  proved two releases coexist in one process on a Pixel 3 (identical results, no interference).
- The typed command API differs between releases (`chonk_start(num_circuits)` in 5.x,
  `chonk_start(kinds)` in 6.x). `azwall-prover` depends on each `barretenberg-rs` version under
  a renamed dependency and implements one small `ChonkApi` binding per version
  (`src/bindings/v5.rs`, `v6.rs`). The Chonk sequence itself is written once
  (`ReleaseBackend`).
- `ProverManager` keeps **one release loaded at a time**: a release costs its library plus its
  own CRS copy (≈ 200 MiB once initialised), and the wallet uses one network at a time.
  Switching network unloads the previous release before loading the next.
- Requests and responses use the prototype's `AZS2`/`AZP1` framing, so captured requests remain
  valid fixtures.

### Adding an Aztec version

1. Add the network to `networks.json` and its `AZWALL_RPC_*` key to `.env.example`.
2. If its Barretenberg release is new:
   - same API as an existing binding: add the release to `RELEASES` in
     `rust/azwall-prover/src/bindings/mod.rs` only if the msgpack schema is unchanged (otherwise
     treat it as a new API);
   - new API: add `bbN = { package = "barretenberg-rs", version = "=<release>", … }` and a
     `bb-vN` feature to `rust/azwall-prover/Cargo.toml`, then `src/bindings/vN.rs`.
3. A PXE host: copy `pxe/v6` to `pxe/vN` (package name, Aztec dependencies, `src/version.ts`,
   `azwallHost.imports` if the package names differ), add it to `pnpm-workspace.yaml`. The host's
   code itself is shared (`pxe/host`); a version only adds what its API changed to `version.ts`.
4. `scripts/build-bb-android.sh <release> arm64-v8a x86_64`.
5. `cargo test`: `registry_releases` fails if a network has no binding, `registry_file` if
   `.env.example` lacks its key.

## The PXE host

`pxe/` holds one JavaScript package per Aztec version (`pxe/v5`, `pxe/v6`), each built into
`assets/pxe/<bundle>/` and run by the apps in a web view the user never sees. The host's code is
written once, in `pxe/host` (with the upstream `@aztec/…` package names); each version package
holds only its `src/version.ts` (what differs: version constant, node client options, proving step
kinds, ABI decoding), its dependencies and its Vite config. `pxe/scripts/sync-host.mjs` copies
`pxe/host` into the package before every build, test or typecheck, renaming imports with the
package's `azwallHost.imports` rules (`@aztec/` → `@aztec-labs/` for 6.x); the copies are not
committed. All versions share `pxe/shared`: the JSON message bridge, the proof framing and
parameter validation.

| | Android | iOS |
|---|---|---|
| Web view | `WebView`, page served from the APK over `https://appassets.androidplatform.net` (`WebViewAssetLoader`) | `WKWebView`, page served by the app on `http://127.0.0.1:476xx` (one fixed port per bundle, so the stores keep their origin) |
| Page → app | `AzwallAndroid.postMessage` (`@JavascriptInterface`) | `webkit.messageHandlers.azwall` |
| App → page | `evaluateJavascript("azwallHost.receive(…)")` | `evaluateJavaScript("azwallHost.receive(…)")` |
| Vault | Keystore AES-GCM key, biometric or device credential | Keychain item with `.userPresence` |

Both origins are secure contexts, which the PXE's Web Crypto and its encrypted SQLite-OPFS stores require.
The app calls the host (`open`, `addAccount`, `balances`, `transfer`…); the host calls the app back for
`prove`, which runs the network's Barretenberg release in Rust.

## Not done yet

- **iOS has not been compiled yet** (written without a Mac), and Barretenberg is not built for iOS: build each
  release as a dynamic framework named `AzBB_<release>.framework` (see `rust/azwall-prover/src/library.rs`).
- **Migration between versions**: when a network is retired, Aztec asks users to withdraw before a deadline.
  The registry should mark retired networks with that deadline, and the wallet should offer a migration
  (withdraw through the L1 portal on the old rollup, deposit on the new one).
- **dApp browser** (done in the prototype), and receiving notification of incoming private transfers.
- **Native ACVM** for protocol circuits (the prototype's `acvm.rs`), per Noir version.
