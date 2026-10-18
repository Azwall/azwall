//! Binding for Barretenberg 5.x (`barretenberg-rs` 5.1.0): `ChonkStart` takes the circuit count
//! and `ChonkLoad` takes no kind.

use bb5::generated_types::{ChonkProof, CircuitInput};
use bb5::BarretenbergApi;

use super::ChonkApi;
use crate::library::BbLibrary;
use crate::srs::Srs;
use crate::wire::ExecutionStep;

impl bb5::Backend for BbLibrary {
    fn call(&mut self, input: &[u8]) -> bb5::Result<Vec<u8>> {
        BbLibrary::call(self, input).map_err(bb5::BarretenbergError::Backend)
    }

    fn destroy(&mut self) -> bb5::Result<()> {
        Ok(())
    }
}

pub(crate) struct Api(BarretenbergApi<BbLibrary>);

impl Api {
    pub(crate) fn new(library: BbLibrary) -> Self {
        Self(BarretenbergApi::new(library))
    }
}

fn s(e: bb5::BarretenbergError) -> String {
    e.to_string()
}

impl ChonkApi for Api {
    type Proof = ChonkProof;

    fn init_srs(&mut self, srs: &Srs) -> Result<(), String> {
        self.0.srs_init_srs(&srs.g1, srs.g1_points, &srs.g2).map_err(s)?;
        self.0.srs_init_grumpkin_srs(&srs.grumpkin, srs.grumpkin_points).map_err(s)?;
        Ok(())
    }

    fn start(&mut self, kinds: Vec<u32>) -> Result<(), String> {
        self.0.chonk_start(kinds.len() as u32).map(drop).map_err(s)
    }

    fn load(&mut self, step: &mut ExecutionStep) -> Result<(), String> {
        let circuit = CircuitInput {
            name: step.name.clone(),
            bytecode: std::mem::take(&mut step.bytecode),
            verification_key: std::mem::take(&mut step.vk),
        };
        self.0.chonk_load(circuit).map(drop).map_err(s)
    }

    fn accumulate(&mut self, witness: &[u8]) -> Result<(), String> {
        self.0.chonk_accumulate(witness).map(drop).map_err(s)
    }

    fn prove(&mut self) -> Result<ChonkProof, String> {
        self.0.chonk_prove().map(|r| r.proof).map_err(s)
    }

    fn verify(&mut self, proof: ChonkProof, vk: &[u8]) -> Result<bool, String> {
        self.0.chonk_verify(proof, vk).map(|r| r.valid).map_err(s)
    }

    fn compress(&mut self, proof: ChonkProof) -> Result<Vec<u8>, String> {
        self.0.chonk_compress_proof(proof).map(|r| r.compressed_proof).map_err(s)
    }

    fn fields(proof: ChonkProof) -> Vec<Vec<u8>> {
        [proof.hiding_oink_proof, proof.merge_proof, proof.eccvm_proof, proof.ipa_proof, proof.joint_proof]
            .into_iter()
            .flatten()
            .collect()
    }
}
