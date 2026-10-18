//! Native Chonk (ClientIVC) proving for every Aztec version the app embeds.
//!
//! Each Aztec version needs the Barretenberg release its circuits were built with. Every release
//! is shipped as its own shared library exporting only `azbb_api`/`azbb_free`
//! (`scripts/build-bb-shared.sh`), loaded with `dlopen` so several releases coexist in one
//! process. The typed command API differs between releases, hence one [`bindings`] module per
//! Barretenberg API version.
//!
//! - [`wire`]: binary framing of prove requests (from the PXE) and responses.
//! - [`library`]: one loaded Barretenberg shared library.
//! - [`bindings`]: release → API binding table and the Chonk sequence per binding.
//! - [`engine`]: a dedicated prover thread owning one backend.
//! - [`manager`]: keeps one release active at a time and switches on demand.

pub mod backend;
pub mod bindings;
pub mod engine;
pub mod error;
pub mod library;
pub mod manager;
pub mod srs;
pub mod wire;

pub use backend::{ChonkBackend, ChonkOutput, ProveTimings};
pub use error::ProverError;
pub use manager::ProverManager;
pub use srs::Srs;

pub type Result<T> = std::result::Result<T, ProverError>;
