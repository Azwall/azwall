---
title: Several Aztec versions
parent: Design
nav_order: 1
---

# Several Aztec versions at once

## The problem

Aztec is still evolving, and its networks do not all move together. On 18 October 2026:

| Network | Node version | Client packages | Barretenberg | Rollup version |
|---|---|---|---|---|
| Testnet v6 | 6.0.0 | `@aztec-labs/*` 6.0.0 | 6.0.0 | 679261766 |

Testnet v5 (node 5.0.0, `@aztec/*` 5.1.0, Barretenberg 5.1.0) ran alongside until October 2026; the
app dropped it once testnet v6 reached 6.0.0, and mainnet moves to v6 on 26 October 2026. These versions are **not compatible with each other**. The protocol circuits differ (v5 proves
with `private_kernel_init_3` and `private_kernel_inner`, v6 with `private_kernel_init_4`), so do
their verification keys, the proof format and the account contract classes. A client built for v6
cannot send a transaction to a v5 node, and the reverse is also true.

dApps live on one network or the other. A wallet that supports a single version forces users to
choose a wallet per network. Azwall instead offers a **network selector**, like other chains'
wallets do, and the selected network decides which Aztec version runs.

## What is versioned

| Part | Per version? | How versions coexist |
|---|---|---|
| PXE, aztec.js, protocol circuits, ACVM (JavaScript) | Yes | One PXE bundle per version, started only when its network is selected |
| Barretenberg (native prover) | Yes | One shared library per release, loaded with `dlopen` |
| Barretenberg command API (Rust bindings) | Yes, between major releases | One small binding per API version |
| CRS (proving setup files) | No, universal | Shared by every release |
| Accounts | The address depends on the account contract class | One key, one address per network |
| Stores (notes, sync state) | Per network | Scoped by chain and rollup address |
| dApp sessions | Per network | Routed by the `chainInfo` the dApp sends |

## The network registry

Every supported network is one entry in `networks.json`:

```json
{
  "id": "testnet-v6",
  "label": "Testnet (v6)",
  "aztec": "6.0.0",
  "barretenberg": "6.0.0",
  "noir": "1.0.0-rc.3",
  "defaultRpc": "https://aztec-testnet.drpc.org",
  "expected": {
    "l1ChainId": 11155111,
    "rollupVersion": 679261766,
    "vkTreeRoot": "0x22ff…",
    "protocolContractsHash": "0x0f54…"
  }
}
```

- `aztec` selects the PXE bundle, and its major version must match the node's.
- `barretenberg` selects the native prover release.
- `expected` holds what the node must report. Before using a network, the app calls
  `node_getNodeInfo` and compares the L1 chain id, the rollup version and the node's major
  version. A mismatch is shown to the user and blocks the network.
- The RPC endpoint comes from `.env` (`AZWALL_RPC_TESTNET_V6=…`), falling back to `defaultRpc`.
  The build resolves both files into the configuration the apps bundle.

Several networks can share one Barretenberg release: the v6 mainnet and the v6 testnet could use
the same library.

## Two Barretenberg releases in one process

Loading two releases of the same C++ library into one process normally ends in symbol clashes.
Each release is therefore wrapped into a **self-contained shared library**:

- its own static C++ runtime, and every internal symbol kept local;
- only two exported functions, `azbb_api` (a msgpack command in, a msgpack response out) and
  `azbb_free`;
- loaded with `dlopen(RTLD_LOCAL)`, so one release never resolves another's symbols.

The prototype tested this on a Pixel 3 with real proof requests captured on each testnet. Both
libraries were loaded in one process and proved alternately (v5, v6, then v6, v5):

| Round | Release | Circuits | Proof | Valid |
|---|---|---|---|---|
| 1 | 5.1.0 | 8 | 22.9 s | yes |
| 1 | 6.0.0-rc.6 | 7 | 14.6 s | yes |
| 2 | 6.0.0-rc.6 | 7 | 14.4 s | yes |
| 2 | 5.1.0 | 8 | 22.6 s | yes |

The intermediate hashes were identical across rounds, so neither library disturbed the other.

The command API changed between the two releases (`chonk_start(num_circuits)` in 5.x,
`chonk_start(kinds)` in 6.x). The Rust prover depends on both versions of the generated bindings
and implements one small adapter per API version. The proving sequence itself is written once.

## One version loaded at a time

A loaded release costs its library (17 to 20 MB) plus its own copy of the CRS in memory, and
the wallet works on one network at a time. The prover manager therefore keeps **a single release
loaded** and unloads it before loading another when the user switches network. The same applies to
PXE bundles: only the selected network's PXE runs.

## Adding a version

A new Aztec release means a new app release: iOS forbids downloading native code, and each
version's circuits and prover must be reviewed anyway. Adding one takes:

1. an entry in `networks.json`, and its RPC key in `.env.example`;
2. if the Barretenberg release is new, its shared library (`scripts/build-bb-android.sh <release>`)
   and, if its command API changed, a binding in `rust/azwall-prover/src/bindings/`;
3. the PXE bundle for that Aztec version.

The tests fail if a network has no binding or no RPC key, so a half-added version cannot ship.

## When a version is retired

An upgrade does not move funds: it deploys a new rollup, and the funds stay on the old one. Every rollup
that has existed stays reachable through the L1 Registry, and assets bridged from L1 can leave any of
them through their portal. Tokens that exist only on L2 have no automatic path; that depends on their
issuer. In practice, Aztec sets a withdrawal deadline before retiring a version (for v4, June 25, 2026).

Because Azwall embeds every version, the same key keeps opening the old account after an upgrade. The
next step is a migration flow: withdraw to L1 through the portal on the old rollup, then deposit on the
new one, one transaction on each version.
