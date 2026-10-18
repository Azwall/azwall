//! What the engine needs from a Barretenberg release, independent of its API version.

use serde::Serialize;

use crate::srs::Srs;
use crate::wire::ExecutionStep;
use crate::Result;

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProveTimings {
    /// chonkLoad over all circuits (deserialising ACIR, building circuits).
    pub load_ms: f64,
    /// chonkAccumulate over all circuits (folding).
    pub accumulate_ms: f64,
    pub prove_ms: f64,
    /// Every proof is verified before it is returned, as bb.js does.
    pub verify_ms: f64,
    pub compress_ms: f64,
}

#[derive(Debug, Clone)]
pub struct ChonkOutput {
    /// Proof fields (32 bytes each), in bb.js `flattenChonkProofFields()` order.
    pub fields: Vec<Vec<u8>>,
    pub compressed: Vec<u8>,
    pub timings: ProveTimings,
}

/// One Barretenberg release, ready to prove. Implementations are not thread-safe: the engine
/// owns each backend on a dedicated thread.
pub trait ChonkBackend: Send {
    /// The Barretenberg release, e.g. `6.0.0-rc.6`.
    fn release(&self) -> &str;
    fn init_srs(&mut self, srs: &Srs) -> Result<()>;
    fn prove(&mut self, steps: Vec<ExecutionStep>) -> Result<ChonkOutput>;
}
