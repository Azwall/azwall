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
3. `scripts/build-bb-android.sh <release> arm64-v8a x86_64`.
4. `cargo test`: `registry_releases` fails if a network has no binding, `registry_file` if
   `.env.example` lacks its key.

## Not done yet

- **PXE**: the PXE is TypeScript and versioned with Aztec. The plan is one PXE bundle per Aztec
  version, run in a headless JS runtime (hidden WebView / WKWebView, or JavaScriptCore), calling
  `WalletCore.prove` for Chonk proofs. Accounts, notes and dApp sessions then live per network.
- **Barretenberg for iOS**: build each release as a dynamic framework named
  `AzBB_<release>.framework` (see `rust/azwall-prover/src/library.rs`) and embed it.
- **Key storage**: Android Keystore / iOS Keychain with biometric unlock, as in the prototype.
- **Native ACVM** for protocol circuits (the prototype's `acvm.rs`), per Noir version.
