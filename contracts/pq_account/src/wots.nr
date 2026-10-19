// Winternitz one-time signatures (WOTS) over Poseidon2, with many one-time keys under one Merkle root.
//
// Hash-based, so post-quantum: forging needs a Poseidon2 preimage, not a discrete log. Base 4 (W = 4) keeps the
// circuit small: a 254-bit message is 127 base-4 digits, plus a 5-digit checksum, so 132 hash chains of 3 steps.
// Verification walks each chain from the signature to the public key: 396 Poseidon2 calls, always, since a
// circuit cannot skip work; then the 132 chain ends are compressed into one leaf, checked against the root.
//
// A one-time key must sign ONE message: two signatures with the same key reveal enough of the chains to forge.
// The account burns each key with a nullifier, so the network refuses a second use.
use aztec::protocol::hash::poseidon2_hash_with_separator;

pub global W: u32 = 4;
pub global MSG_DIGITS: u32 = 127;
pub global CHECKSUM_DIGITS: u32 = 5;
pub global CHAINS: u32 = 132;

// Domain separators, so a hash of one kind can never stand for another.
global SEP_SECRET: Field = 0x617a77616c6c2d776f74732d736b; // "azwall-wots-sk"
global SEP_CHAIN: Field = 0x617a77616c6c2d776f74732d6368; // "azwall-wots-ch"
global SEP_LEAF: Field = 0x617a77616c6c2d776f74732d6c66; // "azwall-wots-lf"
global SEP_NODE: Field = 0x617a77616c6c2d776f74732d6e64; // "azwall-wots-nd"

/// One step of chain `chain`, from position `step` to `step + 1`.
fn chain_step(x: Field, chain: u32, step: u32) -> Field {
    poseidon2_hash_with_separator([x, chain as Field, step as Field], SEP_CHAIN)
}

/// The message's base-4 digits followed by the checksum's: lowering a message digit raises the checksum, so a
/// signature cannot be moved forward along the chains to sign another message.
pub fn digits(message: Field) -> [u32; CHAINS] {
    // Base-4 digits from bit pairs: 254 bits make 127 digits.
    let bits: [bool; 2 * MSG_DIGITS] = message.to_le_bits();
    let mut checksum: u32 = 0;
    let mut out = [0; CHAINS];
    for i in 0..MSG_DIGITS {
        out[i] = (bits[2 * i] as u32) + 2 * (bits[2 * i + 1] as u32);
        checksum += W - 1 - out[i];
    }
    let check: [bool; 2 * CHECKSUM_DIGITS] = (checksum as Field).to_le_bits();
    for i in 0..CHECKSUM_DIGITS {
        out[MSG_DIGITS + i] = (check[2 * i] as u32) + 2 * (check[2 * i + 1] as u32);
    }
    out
}

/// The leaf a signature of `message` leads to: each chain walked from its digit to the end, then compressed.
pub fn leaf_from_signature(message: Field, signature: [Field; CHAINS]) -> Field {
    let d = digits(message);
    let mut ends = [0; CHAINS];
    for i in 0..CHAINS {
        let mut x = signature[i];
        for step in 0..3 {
            let next = chain_step(x, i, step);
            x = if step >= d[i] { next } else { x };
        }
        ends[i] = x;
    }
    poseidon2_hash_with_separator(ends, SEP_LEAF)
}

/// The Merkle root above `leaf` at `index`, given the siblings from the bottom up.
pub fn root_from_path<let DEPTH: u32>(leaf: Field, index: u32, path: [Field; DEPTH]) -> Field {
    let bits: [bool; DEPTH] = (index as Field).to_le_bits();
    let mut node = leaf;
    for level in 0..DEPTH {
        let (left, right) = if !bits[level] { (node, path[level]) } else { (path[level], node) };
        node = poseidon2_hash_with_separator([left, right], SEP_NODE);
    }
    node
}

/// True if `signature` signs `message` with the one-time key at `index` of the tree with `root`.
pub fn verify<let DEPTH: u32>(root: Field, message: Field, index: u32, signature: [Field; CHAINS], path: [Field; DEPTH]) -> bool {
    root_from_path(leaf_from_signature(message, signature), index, path) == root
}

// Key generation and signing: what the wallet does (in TypeScript); kept here for the tests.

pub fn secret(seed: Field, index: u32, chain: u32) -> Field {
    poseidon2_hash_with_separator([seed, index as Field, chain as Field], SEP_SECRET)
}

pub fn sign(seed: Field, index: u32, message: Field) -> [Field; CHAINS] {
    let d = digits(message);
    let mut signature = [0; CHAINS];
    for i in 0..CHAINS {
        let mut x = secret(seed, index, i);
        for step in 0..3 {
            if step < d[i] {
                x = chain_step(x, i, step);
            }
        }
        signature[i] = x;
    }
    signature
}

pub fn leaf(seed: Field, index: u32) -> Field {
    let mut ends = [0; CHAINS];
    for i in 0..CHAINS {
        let mut x = secret(seed, index, i);
        for step in 0..3 {
            x = chain_step(x, i, step);
        }
        ends[i] = x;
    }
    poseidon2_hash_with_separator(ends, SEP_LEAF)
}

/// Root and the path of `index` for a tree of 2^DEPTH keys (tests only: small trees).
pub fn tree<let DEPTH: u32, let LEAVES: u32>(seed: Field, index: u32) -> (Field, [Field; DEPTH]) {
    let mut level: [Field; LEAVES] = [0; LEAVES];
    for i in 0..LEAVES {
        level[i] = leaf(seed, i);
    }
    let mut path = [0; DEPTH];
    let mut width = LEAVES;
    let mut position = index;
    for d in 0..DEPTH {
        path[d] = level[position ^ 1];
        for i in 0..LEAVES / 2 {
            if i < width / 2 {
                level[i] = poseidon2_hash_with_separator([level[2 * i], level[2 * i + 1]], SEP_NODE);
            }
        }
        width /= 2;
        position /= 2;
    }
    (level[0], path)
}

#[test]
fn checksum_digits_cover_the_worst_case() {
    // All-zero message digits give the largest checksum: 127 * 3 = 381 < 4^5.
    let d = digits(0);
    let mut checksum = 0;
    let mut weight = 1;
    for i in 0..CHECKSUM_DIGITS {
        checksum += d[MSG_DIGITS + i] * weight;
        weight *= W;
    }
    assert_eq(checksum, 381);
}

#[test]
fn a_signature_verifies_against_the_root() {
    let seed = 0x5eed;
    let (root, path) = tree::<2, 4>(seed, 2);
    let message = 0x1234567890abcdef;
    assert(verify(root, message, 2, sign(seed, 2, message), path));
}

#[test]
fn a_signature_does_not_verify_another_message_or_key() {
    let seed = 0x5eed;
    let (root, path) = tree::<2, 4>(seed, 1);
    let signature = sign(seed, 1, 0xaaaa);
    assert(!verify(root, 0xaaab, 1, signature, path));
    assert(!verify(root, 0xaaaa, 3, signature, path));
}

#[test]
fn matches_the_wallet_signer() {
    // pxe/v6/test/wots.test.ts computes the same tree: a mismatch means the wallet's signatures would all fail.
    let (root, _) = tree::<2, 4>(0x5eed, 0);
    assert_eq(root, 0x08bcdda3f1f697a48dbe524ac3acbe0f66c8bb78f40a6d505ea1dc1a7506ef11);
}
