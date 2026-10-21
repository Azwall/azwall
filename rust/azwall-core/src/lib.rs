//! Shared, platform-independent wallet logic.
//!
//! - [`network`]: the registry of supported Aztec networks (`networks.json`), one entry per
//!   Aztec version the app embeds.
//! - [`dotenv`] and [`config`]: RPC endpoints from `.env`, resolved into the config both apps bundle.
//! - [`rpc`]: a minimal Aztec node JSON-RPC client and the node compatibility check.
//! - [`mnemonic`]: the recovery phrase and the account keys derived from it.

pub mod config;
pub mod dotenv;
pub mod error;
pub mod mnemonic;
pub mod network;
pub mod rpc;

pub use config::{AppConfig, ResolvedNetwork};
pub use error::CoreError;
pub use network::{Expected, NetworkSpec, Registry};

pub type Result<T> = std::result::Result<T, CoreError>;
