---
title: Post-quantum account (experiment)
parent: Design
nav_order: 5
---

# Post-quantum account (experiment)

Branch `feat/pq-account`. An Aztec account contract authenticated by hash-based signatures instead of Schnorr,
measured on a Pixel 3 against the Schnorr account the wallet uses, on testnet v6 (Aztec 6.0.0-rc.3).

## Design

- **Winternitz one-time signatures (WOTS) over Poseidon2**, base 4: a 254-bit message is 127 digits plus a 5-digit
  checksum, so 132 hash chains of 3 steps. Verifying walks every chain to its end (396 Poseidon2 calls, a fixed
  amount in a circuit) and compresses the 132 ends into a leaf.
- **1024 one-time keys** under a Merkle root (depth 10) stored in the account at deployment. The auth witness
  carries the key index, the signature and the Merkle path (143 fields). It is a private input: its size costs no
  fee.
- **Each transaction burns its key** with a nullifier, so the network refuses a second use. Authwits run in a static
  call that cannot emit nullifiers: there, not reusing a key is up to the wallet.
- Sources: `contracts/pq_account` (Noir, `scripts/build-contracts.sh`), `pxe/v6/src/pq` (signing, account,
  experiment). The wallet signer and the contract share a test vector.

## Measurements

Circuit size (`aztec profile gates`):

| Function | Schnorr | PQ |
|---|---|---|
| `entrypoint` | 15,002 | 39,798 |
| `verify_private_authwit` | 6,489 | 31,324 |

Pixel 3, native prover, the same transactions sent back to back from each account (`pqExperiment`):

| | Schnorr | PQ |
|---|---|---|
| Key generation (once per account) | – | 158 s |
| Account deployment: proof (7 circuits) | 14.8 s | 16.9 s |
| Token deployment from the account: proof (6 circuits) | 14.6 s | 15.4 s |
| Token deployment: simulation, signing and proof | 22.7 s | 25.9 s |

The account circuit is 2.65 times larger, but it is a small part of the proof: the kernels dominate. A transaction
from the PQ account costs about **+1 s of proving and +3 s end to end** (+14 %); most of the gap is signing in
JavaScript (396 Poseidon2 calls, plus the larger circuit's witness).

## Limits

- **Key generation is slow** (2.6 min on the phone, ~450,000 Poseidon2 calls in JavaScript) and **the number of
  transactions is bounded** (1024 here). A bigger tree or a hypertree (XMSS^MT-like) would push both.
- **State**: the wallet must never sign two messages with one key; the experiment keeps the next index in memory.
- **Not post-quantum overall**: it protects who can spend. Note encryption and address keys use the Grumpkin curve
  (privacy could be broken later, "harvest now, decrypt later"), Aztec's proofs use elliptic curves, and L1 and the
  rollup's validators sign with ECDSA.
- **Gas**: EmbeddedWallet estimates gas through a per-type account stub it lacks for this account; PQ transactions
  are proven directly with the network's admission limits (fee sponsored on testnet).
