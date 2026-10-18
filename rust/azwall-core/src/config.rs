//! The configuration both apps bundle (`assets/azwall-config.json`): the network registry with
//! each network's RPC endpoint resolved from `.env`, so the apps never parse `.env` themselves.

use serde::{Deserialize, Serialize};

use crate::dotenv::{self, EnvMap};
use crate::network::{is_http_url, NetworkSpec, Registry};
use crate::{CoreError, Result};

pub const MAX_BATCH_KEY: &str = "AZWALL_RPC_MAX_BATCH";
/// The free public dRPC endpoint rejects JSON-RPC batches larger than this.
pub const DEFAULT_MAX_BATCH: u32 = 3;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedNetwork {
    #[serde(flatten)]
    pub spec: NetworkSpec,
    pub rpc_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    pub default_network: String,
    pub networks: Vec<ResolvedNetwork>,
    pub rpc_max_batch: u32,
    /// Registry networks left out because no RPC endpoint is known for them.
    #[serde(default)]
    pub unavailable: Vec<String>,
}

impl AppConfig {
    /// Resolves every network's RPC URL: `.env` first, then the registry's `defaultRpc`.
    /// A network with neither is listed in `unavailable`; the default network must resolve.
    pub fn resolve(registry: &Registry, env: &EnvMap) -> Result<Self> {
        let mut networks = Vec::new();
        let mut unavailable = Vec::new();
        for spec in &registry.networks {
            let key = spec.rpc_env_key();
            let url = dotenv::non_empty(env, &key).or(spec.default_rpc.as_deref());
            match url {
                Some(url) if is_http_url(url) => networks.push(ResolvedNetwork { spec: spec.clone(), rpc_url: url.to_string() }),
                Some(_) => return Err(CoreError::Config(format!("{key} must be an http(s) URL"))),
                None => unavailable.push(spec.id.clone()),
            }
        }
        if unavailable.contains(&registry.default) {
            let key = crate::network::rpc_env_key(&registry.default);
            return Err(CoreError::Config(format!("the default network has no RPC endpoint: set {key} in .env")));
        }
        Ok(Self { default_network: registry.default.clone(), networks, rpc_max_batch: max_batch(env)?, unavailable })
    }

    pub fn from_json(json: &str) -> Result<Self> {
        let config: AppConfig = serde_json::from_str(json).map_err(|e| CoreError::Config(e.to_string()))?;
        if config.network(&config.default_network).is_err() {
            return Err(CoreError::Config(format!("default network '{}' is missing", config.default_network)));
        }
        Ok(config)
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string_pretty(self).expect("AppConfig always serialises")
    }

    pub fn network(&self, id: &str) -> Result<&ResolvedNetwork> {
        self.networks.iter().find(|n| n.spec.id == id).ok_or_else(|| CoreError::UnknownNetwork(id.to_string()))
    }
}

fn max_batch(env: &EnvMap) -> Result<u32> {
    match dotenv::non_empty(env, MAX_BATCH_KEY) {
        None => Ok(DEFAULT_MAX_BATCH),
        Some(v) => v
            .parse::<u32>()
            .ok()
            .filter(|n| *n > 0)
            .ok_or_else(|| CoreError::Config(format!("{MAX_BATCH_KEY} must be a positive integer, got '{v}'"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const REGISTRY: &str = r#"{
      "schema": 1,
      "default": "net-a",
      "networks": [
        { "id": "net-a", "label": "A", "aztec": "6.0.0", "barretenberg": "6.0.0",
          "defaultRpc": "https://a.example", "expected": { "l1ChainId": 1, "rollupVersion": 10 } },
        { "id": "net-b", "label": "B", "aztec": "5.1.0", "barretenberg": "5.1.0",
          "expected": { "l1ChainId": 1, "rollupVersion": 20 } }
      ]
    }"#;

    fn env(pairs: &[(&str, &str)]) -> EnvMap {
        pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    #[test]
    fn env_overrides_the_default_rpc() {
        let registry = Registry::from_json(REGISTRY).unwrap();
        let config = AppConfig::resolve(&registry, &env(&[("AZWALL_RPC_NET_A", "https://keyed.example/k")])).unwrap();
        assert_eq!(config.network("net-a").unwrap().rpc_url, "https://keyed.example/k");
    }

    #[test]
    fn networks_without_rpc_are_unavailable() {
        let registry = Registry::from_json(REGISTRY).unwrap();
        let config = AppConfig::resolve(&registry, &env(&[("AZWALL_RPC_NET_B", "")])).unwrap();
        assert_eq!(config.networks.len(), 1);
        assert_eq!(config.unavailable, ["net-b"]);
        assert_eq!(config.rpc_max_batch, DEFAULT_MAX_BATCH);
    }

    #[test]
    fn the_default_network_must_resolve() {
        let json = REGISTRY.replace("\"defaultRpc\": \"https://a.example\",", "");
        let registry = Registry::from_json(&json).unwrap();
        let err = AppConfig::resolve(&registry, &EnvMap::new()).unwrap_err();
        assert!(err.to_string().contains("AZWALL_RPC_NET_A"));
    }

    #[test]
    fn rejects_bad_urls_and_batch_sizes() {
        let registry = Registry::from_json(REGISTRY).unwrap();
        assert!(AppConfig::resolve(&registry, &env(&[("AZWALL_RPC_NET_B", "node:8080")])).is_err());
        assert!(AppConfig::resolve(&registry, &env(&[(MAX_BATCH_KEY, "0")])).is_err());
        let config = AppConfig::resolve(&registry, &env(&[(MAX_BATCH_KEY, "16")])).unwrap();
        assert_eq!(config.rpc_max_batch, 16);
    }

    #[test]
    fn json_round_trip() {
        let registry = Registry::from_json(REGISTRY).unwrap();
        let config = AppConfig::resolve(&registry, &env(&[("AZWALL_RPC_NET_B", "http://localhost:8080")])).unwrap();
        let back = AppConfig::from_json(&config.to_json()).unwrap();
        assert_eq!(back, config);
        assert!(config.to_json().contains("\"rpcUrl\""));
    }

    #[test]
    fn from_json_checks_the_default() {
        let json = r#"{ "defaultNetwork": "x", "networks": [], "rpcMaxBatch": 3 }"#;
        assert!(AppConfig::from_json(json).is_err());
    }
}
