//! The recovery phrase: BIP-39 words, and the Aztec signing keys of the accounts derived from them
//! (docs/design/recovery-phrase.md). Changing anything here changes every restored address: the test vectors
//! freeze it.

use bip39::{Language, Mnemonic};
use num_bigint::BigUint;
use sha2::{Digest, Sha512};

use crate::{CoreError, Result};

/// Words of a new phrase: 24, 256 bits of entropy, as Zcash wallets do.
pub const NEW_PHRASE_WORDS: usize = 24;

/// Domain separation of the account key derivation; a new version would be a new scheme.
const ACCOUNT_KEY_DOMAIN: &[u8] = b"azwall/aztec/account-signing-key/v1";

/// Order of the Grumpkin scalar field, which Aztec signing keys belong to.
const GRUMPKIN_ORDER: &str = "30644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd47";

fn invalid(e: impl std::fmt::Display) -> CoreError {
    CoreError::Phrase(format!("this is not a valid recovery phrase: {e}"))
}

/// A new phrase from the system's random generator.
pub fn generate() -> Result<String> {
    let mut entropy = [0u8; NEW_PHRASE_WORDS / 3 * 4];
    getrandom::fill(&mut entropy).map_err(|e| CoreError::Config(format!("no random source: {e}")))?;
    let phrase = Mnemonic::from_entropy_in(Language::English, &entropy).map_err(invalid)?.to_string();
    entropy.fill(0);
    Ok(phrase)
}

/// The phrase as stored: lowercase words separated by single spaces. Fails on an unknown word, a wrong word count
/// or a wrong checksum (a typo), naming the first unknown word when there is one.
pub fn normalize(phrase: &str) -> Result<String> {
    let words: Vec<String> = phrase.split_whitespace().map(str::to_lowercase).collect();
    if let Some(word) = words.iter().find(|w| Language::English.find_word(w).is_none()) {
        return Err(invalid(format!("\"{word}\" is not in the word list")));
    }
    Ok(Mnemonic::parse_in(Language::English, words.join(" ")).map_err(invalid)?.to_string())
}

/// The signing key of account `index`, as 0x + 64 hex digits:
/// `SHA-512(domain || BIP-39 seed || index as 4 bytes big-endian) mod Grumpkin order`.
pub fn account_signing_key(phrase: &str, index: u32) -> Result<String> {
    let mnemonic = Mnemonic::parse_in(Language::English, normalize(phrase)?).map_err(invalid)?;
    let seed = mnemonic.to_seed("");
    let digest = Sha512::new().chain_update(ACCOUNT_KEY_DOMAIN).chain_update(seed).chain_update(index.to_be_bytes()).finalize();
    let order = BigUint::parse_bytes(GRUMPKIN_ORDER.as_bytes(), 16).expect("constant");
    let key = BigUint::from_bytes_be(&digest) % order;
    if key == BigUint::ZERO {
        return Err(CoreError::Phrase(format!("account {index} has no valid key; use the next one")));
    }
    Ok(format!("0x{key:064x}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ABANDON: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon \
                           abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";

    #[test]
    fn new_phrases_have_24_valid_words() {
        let phrase = generate().unwrap();
        assert_eq!(phrase.split(' ').count(), 24);
        assert_eq!(normalize(&phrase).unwrap(), phrase);
        assert_ne!(phrase, generate().unwrap());
    }

    #[test]
    fn phrases_are_normalized_and_checked() {
        assert_eq!(normalize(&format!("  {}\n", ABANDON.to_uppercase().replace(' ', "  "))).unwrap(), ABANDON);
        assert!(normalize(&ABANDON.replace(" art", " zoo")).unwrap_err().to_string().contains("not a valid recovery phrase"));
        assert!(normalize(&ABANDON.replace(" art", " azwall")).unwrap_err().to_string().contains("\"azwall\""));
        assert!(normalize("abandon abandon").is_err());
        // 12-word phrases from other wallets are accepted too.
        assert!(normalize("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about").is_ok());
    }

    /// Frozen: a change here changes the address of every account restored from a phrase.
    #[test]
    fn account_keys_are_frozen() {
        let first = account_signing_key(ABANDON, 0).unwrap();
        let second = account_signing_key(ABANDON, 1).unwrap();
        // Cross-checked with Python's hashlib (pbkdf2_hmac, sha512) and the BIP-39 seed of this phrase.
        assert_eq!(first, "0x0482ad4b50d72a8ecc2285589ddc9df165af34149e32612656fa28d2d26e623c");
        assert_eq!(second, "0x06323dc029ceb9ea37935f11d3758e169847c127412b5e40271babc95abfb674");
        assert_eq!(account_signing_key(&ABANDON.to_uppercase(), 0).unwrap(), first, "normalized first");
    }
}
