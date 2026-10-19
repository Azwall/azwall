mod wots;
mod root_note;

// Account contract authenticated by post-quantum (hash-based) signatures: Winternitz one-time signatures over
// Poseidon2, with 2^DEPTH one-time keys under a Merkle root fixed at deployment (see wots.nr).
//
// Experimental. Each transaction burns its one-time key with a nullifier, so a key can never sign twice through the
// entrypoint. Authwits (verify_private_authwit) run in a static call that cannot emit nullifiers: there, never
// reusing a key is up to the wallet.
use aztec::macros::aztec;

#[aztec]
pub contract PqAccount {
    use aztec::{
        authwit::{account::AccountActions, entrypoint::app::AppPayload},
        context::PrivateContext,
        macros::{functions::{allow_phase_change, external, initializer, noinitcheck, view}, storage::storage},
        messages::delivery::MessageDelivery,
        oracle::auth_witness::get_auth_witness,
        protocol::hash::poseidon2_hash_with_separator,
        state_vars::SinglePrivateImmutable,
    };

    use crate::{root_note::RootNote, wots};

    /// 1024 one-time keys: one per transaction or authorization.
    global DEPTH: u32 = 10;
    /// Auth witness layout: [key index, signature (CHAINS fields), Merkle path (DEPTH fields)].
    global WITNESS_LEN: u32 = 1 + 132 + 10;
    global SEP_KEY_USED: Field = 0x617a77616c6c2d776f74732d7573; // "azwall-wots-us"

    #[storage]
    struct Storage<Context> {
        signing_root: SinglePrivateImmutable<RootNote, Context>,
    }

    #[external("private")]
    #[initializer]
    fn constructor(signing_root: Field) {
        self.storage.signing_root.initialize(RootNote { root: signing_root }).deliver(
            MessageDelivery::onchain_unconstrained().with_sender(self.address),
        );
    }

    #[external("private")]
    #[noinitcheck]
    #[allow_phase_change]
    fn entrypoint(app_payload: AppPayload, fee_payment_method: u8, cancellable: bool) {
        let actions = AccountActions::init(self.context, is_valid_and_burn);
        actions.entrypoint(app_payload, fee_payment_method, cancellable);
    }

    #[external("private")]
    #[noinitcheck]
    #[view]
    fn verify_private_authwit(inner_hash: Field) -> Field {
        let actions = AccountActions::init(self.context, is_valid);
        actions.verify_private_authwit(inner_hash)
    }

    /// Verifies the signature and burns its one-time key: a second transaction with the same key is refused.
    #[contract_library_method]
    fn is_valid_and_burn(context: &mut PrivateContext, outer_hash: Field) -> bool {
        let (valid, index) = check(context, outer_hash);
        // Domain-separated (SEP_KEY_USED) and siloed by this contract's address by the protocol.
        context.push_nullifier_unsafe(poseidon2_hash_with_separator([index as Field], SEP_KEY_USED));
        valid
    }

    #[contract_library_method]
    fn is_valid(context: &mut PrivateContext, outer_hash: Field) -> bool {
        check(context, outer_hash).0
    }

    #[contract_library_method]
    fn check(context: &mut PrivateContext, outer_hash: Field) -> (bool, u32) {
        let storage = Storage::init(context);
        let root = storage.signing_root.get_note().root;
        // Safety: the witness only has to make the verification below pass; a wrong one fails it.
        let w: [Field; WITNESS_LEN] = unsafe { get_auth_witness(outer_hash) };
        let index = w[0] as u32;
        // The index must fit the tree, or two indices could share a leaf position.
        assert(index < (1 << DEPTH), "key index out of range");
        let mut signature = [0; wots::CHAINS];
        for i in 0..wots::CHAINS {
            signature[i] = w[1 + i];
        }
        let mut path = [0; DEPTH];
        for i in 0..DEPTH {
            path[i] = w[1 + wots::CHAINS + i];
        }
        (wots::verify(root, outer_hash, index, signature, path), index)
    }
}
