---
title: Recovery phrase
parent: Design
nav_order: 5
---

# Recovery phrase

A wallet is backed up with **24 words**: a standard BIP-39 phrase (English word list, built-in checksum),
as Zcash wallets do. Every account the wallet creates is derived from it, in order, so the phrase alone
brings them back. The scheme is implemented once, in Rust (`rust/azwall-core/src/mnemonic.rs`), and
frozen by test vectors: changing it would change the address of every restored account.

## From words to accounts

1. **Phrase.** 256 bits from the system's random generator, encoded as 24 BIP-39 words. Restoring also
   accepts 12, 15, 18 and 21 words; words are case-insensitive and separated by any whitespace.
2. **Seed.** The standard BIP-39 seed: PBKDF2-HMAC-SHA512, 2048 iterations, salt `"mnemonic"` and an
   empty passphrase, 64 bytes.
3. **Account `i`.** Its Aztec signing key is

   ```
   SHA-512("azwall/aztec/account-signing-key/v1" || seed || i as 4 bytes, big-endian)
       interpreted as a big-endian integer, modulo the Grumpkin scalar field order
   ```

   (0x30644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd47). A zero result, which
   cannot happen in practice, is refused rather than replaced.
4. **Address.** As for any Azwall account: the signing key is the account's root, its privacy keys
   derive from it (`deriveSecretKeyFromSigningKey`), salt 0. The same key gives a different address
   on each Aztec version, like every account.

## Test vectors

Phrase: `abandon` × 23, then `art`.

| Account | Signing key |
|---|---|
| 0 | `0x0482ad4b50d72a8ecc2285589ddc9df165af34149e32612656fa28d2d26e623c` |
| 1 | `0x06323dc029ceb9ea37935f11d3758e169847c127412b5e40271babc95abfb674` |

They are checked by the Rust tests and were cross-checked with Python's `hashlib`.

## In the app

- **Creating** a wallet shows the 24 words once, then asks for three of them at random places before
  creating it. Screens showing words are kept out of screenshots on Android and covered in the iOS app
  switcher; there is no copy button, the phrase is meant to be written down.
- **Restoring** takes a phrase (account 0) or a 64-character signing key. Adding accounts again brings
  the phrase's next ones back, in the same order.
- **Adding an account** takes the phrase's next index. An account imported from its key has no index
  and is not covered by the phrase: its key must be backed up on its own (Settings, Show secret key).
- Wallets created before recovery phrases have none: their accounts keep random keys, each backed up
  on its own.
- The phrase lives in the vault, with the keys, and is shown again from Settings after
  authentication.

## Other wallets

There is no Aztec standard for deriving accounts from a phrase. Azguard encodes a random master
secret as 24 BIP-39 words (no PBKDF2 seed) and derives its accounts with Poseidon2 for its own
account contract, so phrases cannot be moved between the two wallets; a signing key can, account by
account.
