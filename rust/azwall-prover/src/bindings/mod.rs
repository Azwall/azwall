//! Barretenberg API bindings, one per command-API version, and the release → binding table.
//!
//! The Chonk command sequence is the same for every release (start, then load + accumulate per
//! circuit, prove, verify, compress); only the command signatures differ. Each binding implements
//! [`ChonkApi`] over its own `barretenberg-rs` version, and [`ReleaseBackend`] runs the sequence.

use std::time::Instant;

use crate::backend::{ChonkBackend, ChonkOutput, ProveTimings};
use crate::library::BbLibrary;
use crate::srs::Srs;
use crate::wire::ExecutionStep;
use crate::{ProverError, Result};

#[cfg(feature = "bb-v5")]
mod v5;
#[cfg(feature = "bb-v6")]
mod v6;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Binding {
    /// `barretenberg-rs` 5.1.0: `chonk_start(num_circuits)`, `chonk_load(circuit)`.
    #[cfg(feature = "bb-v5")]
    V5,
    /// `barretenberg-rs` 6.0.0-rc.6: `chonk_start(kinds)`, `chonk_load(circuit, kind)`.
    #[cfg(feature = "bb-v6")]
    V6,
}

/// Every Barretenberg release this build can drive. The msgpack schema is tied to the exact
/// release, so a release is only supported with the binding generated from it.
pub const RELEASES: &[(&str, Binding)] = &[
    #[cfg(feature = "bb-v5")]
    ("5.1.0", Binding::V5),
    #[cfg(feature = "bb-v6")]
    ("6.0.0-rc.6", Binding::V6),
];

pub fn binding_for(release: &str) -> Option<Binding> {
    RELEASES.iter().find(|(r, _)| *r == release).map(|(_, b)| *b)
}

pub fn supported_releases() -> Vec<&'static str> {
    RELEASES.iter().map(|(r, _)| *r).collect()
}

/// Wraps a loaded library in the backend for its release.
pub fn open(release: &str, library: BbLibrary) -> Result<Box<dyn ChonkBackend>> {
    match binding_for(release) {
        #[cfg(feature = "bb-v5")]
        Some(Binding::V5) => Ok(Box::new(ReleaseBackend::new(release, v5::Api::new(library)))),
        #[cfg(feature = "bb-v6")]
        Some(Binding::V6) => Ok(Box::new(ReleaseBackend::new(release, v6::Api::new(library)))),
        None => {
            drop(library);
            Err(ProverError::UnsupportedRelease(release.to_string()))
        }
    }
}

/// The Chonk commands, with each binding's signatures smoothed over. Errors are plain messages;
/// [`ReleaseBackend`] adds the release and the command name.
pub(crate) trait ChonkApi: Send {
    type Proof: Clone;
    fn init_srs(&mut self, srs: &Srs) -> std::result::Result<(), String>;
    fn start(&mut self, kinds: Vec<u32>) -> std::result::Result<(), String>;
    fn load(&mut self, step: &mut ExecutionStep) -> std::result::Result<(), String>;
    fn accumulate(&mut self, witness: &[u8]) -> std::result::Result<(), String>;
    fn prove(&mut self) -> std::result::Result<Self::Proof, String>;
    fn verify(&mut self, proof: Self::Proof, vk: &[u8]) -> std::result::Result<bool, String>;
    fn compress(&mut self, proof: Self::Proof) -> std::result::Result<Vec<u8>, String>;
    /// Proof fields in bb.js `flattenChonkProofFields()` order.
    fn fields(proof: Self::Proof) -> Vec<Vec<u8>>;
}

pub(crate) struct ReleaseBackend<A: ChonkApi> {
    release: String,
    api: A,
}

impl<A: ChonkApi> ReleaseBackend<A> {
    pub(crate) fn new(release: &str, api: A) -> Self {
        Self { release: release.to_string(), api }
    }

    fn err(&self, command: impl Into<String>) -> impl Fn(String) -> ProverError {
        let (release, command) = (self.release.clone(), command.into());
        move |message| ProverError::bb(&release, format!("{command}: {message}"))
    }
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

impl<A: ChonkApi> ChonkBackend for ReleaseBackend<A> {
    fn release(&self) -> &str {
        &self.release
    }

    fn init_srs(&mut self, srs: &Srs) -> Result<()> {
        self.api.init_srs(srs).map_err(self.err("SrsInit"))
    }

    fn prove(&mut self, steps: Vec<ExecutionStep>) -> Result<ChonkOutput> {
        let mut timings = ProveTimings::default();
        let hiding_vk = steps.last().ok_or_else(|| ProverError::Wire("no execution steps".into()))?.vk.clone();
        self.api.start(steps.iter().map(|s| s.kind).collect()).map_err(self.err("ChonkStart"))?;
        for mut step in steps {
            if step.vk.is_empty() {
                return Err(ProverError::bb(&self.release, format!("missing precomputed VK for {}", step.name)));
            }
            let t = Instant::now();
            self.api.load(&mut step).map_err(self.err(format!("ChonkLoad {}", step.name)))?;
            timings.load_ms += ms(t);
            let t = Instant::now();
            self.api.accumulate(&step.witness).map_err(self.err(format!("ChonkAccumulate {}", step.name)))?;
            timings.accumulate_ms += ms(t);
        }
        let t = Instant::now();
        let proof = self.api.prove().map_err(self.err("ChonkProve"))?;
        timings.prove_ms = ms(t);
        let t = Instant::now();
        let valid = self.api.verify(proof.clone(), &hiding_vk).map_err(self.err("ChonkVerify"))?;
        timings.verify_ms = ms(t);
        if !valid {
            return Err(ProverError::bb(&self.release, "the Chonk proof does not verify"));
        }
        let t = Instant::now();
        let compressed = self.api.compress(proof.clone()).map_err(self.err("ChonkCompressProof"))?;
        timings.compress_ms = ms(t);
        Ok(ChonkOutput { fields: A::fields(proof), compressed, timings })
    }
}

#[cfg(test)]
mod tests;
