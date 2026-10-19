use aztec::{macros::notes::note, protocol::traits::Packable};

/// The Merkle root of the account's one-time public keys, fixed at deployment.
#[derive(Eq, Packable)]
#[note]
pub struct RootNote {
    pub root: Field,
}
