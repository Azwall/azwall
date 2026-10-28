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
    /// The Ethereum endpoints for the network's Fee Juice deposits, in the order they are tried: `.env`'s, then the
    /// registry's `l1.rpc` and `l1.fallbackRpc`. Empty when the network has no Ethereum side.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub l1_rpc_urls: Vec<String>,
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

impl ResolvedNetwork {
    /// The `network` parameter of the PXE host's `open` call (pxe/*/src/open.ts, `NetworkParams`).
    pub fn pxe_open_params(&self, rpc_max_batch: u32) -> serde_json::Value {
        serde_json::json!({
            "id": self.spec.id,
            "rpcUrl": self.rpc_url,
            "rpcMaxBatch": rpc_max_batch,
            "aztec": self.spec.aztec,
            "expected": self.spec.expected,
            "fees": self.spec.fees,
            // Without it the wallet does not look for bridged Fee Juice.
            "l1": self.spec.l1.as_ref().filter(|_| !self.l1_rpc_urls.is_empty()).map(|l1| serde_json::json!({
                "rpcUrls": self.l1_rpc_urls,
                "portal": l1.portal,
                "portalFromBlock": l1.portal_from_block,
            })),
        })
    }
}

/// A network's Ethereum endpoints, in order: `.env`'s, then the registry's main and fallback ones; none when the
/// registry declares no `l1`.
fn l1_rpcs(spec: &NetworkSpec, env: &EnvMap) -> Result<Vec<String>> {
    let Some(l1) = &spec.l1 else { return Ok(Vec::new()) };
    let key = spec.l1_rpc_env_key();
    let configured = dotenv::non_empty(env, &key);
    if configured.is_some_and(|url| !is_http_url(url)) {
        return Err(CoreError::Config(format!("{key} must be an http(s) URL")));
    }
    let mut urls: Vec<String> = Vec::new();
    for url in configured.into_iter().chain([l1.rpc.as_str()]).chain(l1.fallback_rpc.as_deref()) {
        if !urls.iter().any(|u| u == url) {
            urls.push(url.to_string());
        }
    }
    Ok(urls)
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
                Some(url) if is_http_url(url) => networks.push(ResolvedNetwork {
                    spec: spec.clone(),
                    rpc_url: url.to_string(),
                    l1_rpc_urls: l1_rpcs(spec, env)?,
                }),
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

    /**
     * The hosts of the endpoints `.env` put in place of the registry's: a release must ship none, as an RPC URL of
     * one's own may carry its API key, readable by anyone in the app.
     */
    pub fn endpoints_from_env(&self) -> Vec<String> {
        let mut hosts = Vec::new();
        for n in &self.networks {
            let public: Vec<&str> = n.spec.default_rpc.iter().map(String::as_str)
                .chain(n.spec.l1.iter().flat_map(|l1| std::iter::once(l1.rpc.as_str()).chain(l1.fallback_rpc.as_deref())))
                .collect();
            for url in std::iter::once(&n.rpc_url).chain(&n.l1_rpc_urls) {
                if !public.contains(&url.as_str()) {
                    // Only the host: the rest of the URL may be the key itself.
                    hosts.push(crate::rpc::redact_urls(url).trim_end_matches("/…").to_string());
                }
            }
        }
        hosts
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
        { "id": "net-a", "label": "A", "aztec": "6.0.0", "barretenberg": "6.0.0", "pxe": "v6",
          "defaultRpc": "https://a.example", "expected": { "l1ChainId": 1, "rollupVersion": 10 }, "fees": "sponsored",
          "l1": { "rpc": "https://eth.example", "fallbackRpc": "https://eth2.example", "portal": "0xabababababababababababababababababababab", "portalFromBlock": 42 } },
        { "id": "net-b", "label": "B", "aztec": "5.1.0", "barretenberg": "5.1.0", "pxe": "v5",
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
    fn pxe_open_params_match_the_host_api() {
        let registry = Registry::from_json(REGISTRY).unwrap();
        let config = AppConfig::resolve(&registry, &env(&[("AZWALL_RPC_NET_B", "https://b.example")])).unwrap();
        let params = config.network("net-a").unwrap().pxe_open_params(config.rpc_max_batch);
        assert_eq!(params["rpcUrl"], "https://a.example");
        assert_eq!(params["rpcMaxBatch"], 3);
        assert_eq!(params["expected"]["rollupVersion"], 10);
        assert!(params["expected"].get("vkTreeRoot").is_none());
        assert_eq!(params["fees"], "sponsored");
        // Undeclared, a network has no sponsor: its accounts pay with Fee Juice.
        assert_eq!(config.network("net-b").unwrap().pxe_open_params(3)["fees"], "feeJuice");
    }

    #[test]
    fn the_ethereum_side_reaches_the_pxe_from_env_or_registry() {
        let registry = Registry::from_json(REGISTRY).unwrap();
        let config = AppConfig::resolve(&registry, &env(&[("AZWALL_RPC_NET_B", "https://b.example")])).unwrap();
        let l1 = &config.network("net-a").unwrap().pxe_open_params(3)["l1"];
        assert_eq!(l1["rpcUrls"], serde_json::json!(["https://eth.example", "https://eth2.example"]));
        assert_eq!(l1["portalFromBlock"], 42);
        assert_eq!(l1["portal"], "0xabababababababababababababababababababab");
        // A network that declares no Ethereum side does not look for bridged Fee Juice.
        assert!(config.network("net-b").unwrap().pxe_open_params(3)["l1"].is_null());
        let keyed = AppConfig::resolve(&registry, &env(&[("AZWALL_L1_RPC_NET_A", "https://my-eth.example/k")])).unwrap();
        // net-b has no public endpoint: its own comes from .env, and a release would refuse it.
        assert_eq!(config.endpoints_from_env(), ["https://b.example"]);
        assert_eq!(keyed.endpoints_from_env(), ["https://my-eth.example"], "the key stays out of the message");
        // A keyed endpoint of the user's own comes first; the public ones stay as fallbacks.
        assert_eq!(
            keyed.network("net-a").unwrap().pxe_open_params(3)["l1"]["rpcUrls"],
            serde_json::json!(["https://my-eth.example/k", "https://eth.example", "https://eth2.example"]),
        );
        assert!(AppConfig::resolve(&registry, &env(&[("AZWALL_L1_RPC_NET_A", "ftp://x")])).is_err());
    }

    #[test]
    fn from_json_checks_the_default() {
        let json = r#"{ "defaultNetwork": "x", "networks": [], "rpcMaxBatch": 3 }"#;
        assert!(AppConfig::from_json(json).is_err());
    }
}
