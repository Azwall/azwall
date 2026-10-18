---
title: The PXE stays in JavaScript
parent: Design
nav_order: 3
---

# The PXE stays in JavaScript

## What the PXE does

The PXE (Private eXecution Environment) is the client half of Aztec. It holds the account keys'
view of the chain, syncs and decrypts notes, simulates and executes private functions, answers
the circuits' oracle calls, generates witnesses and assembles transactions. It is a large
TypeScript code base that changes with almost every Aztec release.

## Why not rewrite it

Rewriting the PXE in Rust, Kotlin or Swift would mean following every protocol change by hand:
new oracles, new note formats, new kernel circuits, new store schemas. Aztec ships changes faster
than a port can follow, and a port that lags behind cannot talk to the network at all.

Keeping Aztec's own PXE means:

- **Upgrading is bumping a package.** A new Aztec version is a new PXE bundle built from the
  official `@aztec/*` or `@aztec-labs/*` packages, pinned to exact versions.
- **Same behaviour as Aztec's reference wallets.** The simulation, the gas estimation and the
  transaction format are the ones the network expects.
- **Several versions are just several bundles.** Each [network](multi-version.md) gets the PXE
  bundle of its Aztec version, with its own protocol circuits and verification keys.

The one expensive step, the Chonk proof, does not need to stay in JavaScript.

## Where native code plugs in

The PXE was designed to accept a custom prover. When it is created, it takes an optional
`proverOrOptions`. Any object with a `createChonkProof(executionSteps)` method counts as a prover:

```ts
const pxe = await createPXE(node, config, {
  proverOrOptions: {
    // Called once per transaction with every circuit to fold.
    createChonkProof: async (steps) => decodeProof(await native.prove(networkId, encodeSteps(steps))),
  },
  store, // persisted by the app
});
```

Everything else (simulation, witness generation, sync) runs in JavaScript as Aztec wrote it. The
prototype measured where the time goes and found the proof to be the only part where native code
pays off (see [native proving](native-proving.md)).

## Where the JavaScript runs

In the native apps, each PXE bundle runs in a **headless JavaScript runtime** owned by the app,
with no visible web page:

- Android: a hidden `WebView`, the page served from the APK over `https://appassets.androidplatform.net`;
- iOS: a hidden `WKWebView`, the page served by the app on the loopback address.

The runtime only exposes a small bridge to native code: the proving call above, the store, and
the wallet API calls coming from the UI and from dApps. The UI itself stays native.

On a Pixel 3, through this path, an account activation proves in 15.3 s (7 circuits) and a private
token mint in 14.6 s, both accepted and mined on the testnet.

## Costs of the choice

- Simulation and witness generation stay single-threaded JavaScript. On the prototype's tablet,
  simulation was 40 % of the native total until a gas-limit cache removed most of it.
- Each Aztec version adds its PXE bundle (circuits and WebAssembly) to the app size, on top of
  its Barretenberg library.
