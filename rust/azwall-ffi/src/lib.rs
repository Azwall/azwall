//! The API the native apps call (generated into Kotlin and Swift by uniffi).
//!
//! Calls are blocking: Kotlin runs them on `Dispatchers.IO`, Swift off the main actor.

use std::path::PathBuf;
use std::sync::Arc;

use azwall_core::rpc::{check_compatibility, HttpTransport, NodeClient};
use azwall_core::{AppConfig, CoreError, ResolvedNetwork};
use azwall_prover::{bindings, ProverError, ProverManager};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AzwallError {
    #[error("{detail}")]
    Config { detail: String },
    #[error("unknown network '{id}'")]
    UnknownNetwork { id: String },
    #[error("{detail}")]
    Node { detail: String },
    #[error("{detail}")]
    Prover { detail: String },
}

impl From<CoreError> for AzwallError {
    fn from(e: CoreError) -> Self {
        match e {
            CoreError::UnknownNetwork(id) => Self::UnknownNetwork { id },
            CoreError::Rpc(message) => Self::Node { detail: message },
            other => Self::Config { detail: other.to_string() },
        }
    }
}

impl From<ProverError> for AzwallError {
    fn from(e: ProverError) -> Self {
        Self::Prover { detail: e.to_string() }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Network {
    pub id: String,
    pub label: String,
    pub aztec_version: String,
    pub barretenberg_version: String,
    pub rpc_url: String,
    /// PXE host bundle to load: `assets/pxe/<pxe_bundle>/index.html`.
    pub pxe_bundle: String,
    /// False when this build has no Barretenberg binding for the network's release.
    pub prover_supported: bool,
}

impl From<&ResolvedNetwork> for Network {
    fn from(n: &ResolvedNetwork) -> Self {
        Self {
            id: n.spec.id.clone(),
            label: n.spec.label.clone(),
            aztec_version: n.spec.aztec.clone(),
            barretenberg_version: n.spec.barretenberg.clone(),
            rpc_url: n.rpc_url.clone(),
            pxe_bundle: n.spec.pxe.clone(),
            prover_supported: bindings::binding_for(&n.spec.barretenberg).is_some(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NodeStatus {
    pub node_version: String,
    pub l1_chain_id: u64,
    pub rollup_version: u64,
    pub compatible: bool,
    pub mismatches: Vec<String>,
}

#[derive(uniffi::Object)]
pub struct WalletCore {
    config: AppConfig,
    prover: ProverManager,
}

#[uniffi::export]
impl WalletCore {
    /// `config_json`: the bundled `azwall-config.json`. `native_lib_dir`: where the Barretenberg
    /// libraries are (Android `nativeLibraryDir`, iOS `Frameworks`). `crs_dir`: the CRS files.
    /// `prover_threads = 0` uses every core; the count is fixed for the life of the process.
    #[uniffi::constructor]
    pub fn new(config_json: String, native_lib_dir: String, crs_dir: String, prover_threads: u32) -> Result<Arc<Self>, AzwallError> {
        let config = AppConfig::from_json(&config_json)?;
        let prover = ProverManager::native(PathBuf::from(native_lib_dir), PathBuf::from(crs_dir), prover_threads as usize)?;
        Ok(Arc::new(Self { config, prover }))
    }

    pub fn networks(&self) -> Vec<Network> {
        self.config.networks.iter().map(Network::from).collect()
    }

    pub fn default_network_id(&self) -> String {
        self.config.default_network.clone()
    }

    /// Asks the network's node for its info and compares it with the registry's expectations.
    pub fn check_node(&self, network_id: String) -> Result<NodeStatus, AzwallError> {
        let network = self.config.network(&network_id)?;
        let info = NodeClient::new(HttpTransport::default(), network.rpc_url.clone()).node_info()?;
        let compat = check_compatibility(&network.spec, info);
        Ok(NodeStatus {
            compatible: compat.is_compatible(),
            node_version: compat.node.node_version,
            l1_chain_id: compat.node.l1_chain_id,
            rollup_version: compat.node.rollup_version,
            mismatches: compat.mismatches,
        })
    }

    /// JSON `network` parameter for the PXE host's `open` call.
    pub fn pxe_open_params(&self, network_id: String) -> Result<String, AzwallError> {
        let network = self.config.network(&network_id)?;
        Ok(network.pxe_open_params(self.config.rpc_max_batch).to_string())
    }

    /// Loads the network's Barretenberg release ahead of the first proof (unloading any other).
    pub fn prepare_prover(&self, network_id: String) -> Result<(), AzwallError> {
        let release = &self.config.network(&network_id)?.spec.barretenberg;
        Ok(self.prover.activate(release)?)
    }

    /// Proves an `AZS2` request from the network's PXE; returns the `AZP1` response.
    pub fn prove(&self, network_id: String, request: Vec<u8>) -> Result<Vec<u8>, AzwallError> {
        let release = &self.config.network(&network_id)?.spec.barretenberg;
        Ok(self.prover.prove(release, request)?)
    }

    pub fn active_prover_release(&self) -> Option<String> {
        self.prover.active_release()
    }
}

/// Barretenberg releases this build can prove with.
#[uniffi::export]
pub fn supported_barretenberg_releases() -> Vec<String> {
    bindings::supported_releases().into_iter().map(String::from).collect()
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests;
