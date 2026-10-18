//! The registry of Aztec networks the app supports (`networks.json` at the repository root).
//!
//! Aztec runs several incompatible versions at once (testnet v5 and v6, mainnet alpha…). Each
//! network entry pins the Aztec version its PXE bundle is built from and the Barretenberg
//! release its native prover must use. Several networks may share one Barretenberg release.

use std::collections::{BTreeSet, HashSet};

use serde::{Deserialize, Serialize};

use crate::{CoreError, Result};

pub const REGISTRY_SCHEMA: u32 = 1;

/// Chain values a node must report for the network to be considered compatible.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Expected {
    pub l1_chain_id: u64,
    pub rollup_version: u64,
    /// Root of the protocol circuits' VK tree; checked against the client's circuits.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vk_tree_root: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_contracts_hash: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkSpec {
    pub id: String,
    pub label: String,
    /// Aztec client packages (PXE, aztec.js) version.
    pub aztec: String,
    /// Barretenberg release the native prover loads for this network.
    pub barretenberg: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub noir: Option<String>,
    /// Public endpoint used when `.env` sets none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default_rpc: Option<String>,
    pub expected: Expected,
}

impl NetworkSpec {
    /// The `.env` key holding this network's RPC URL: `testnet-v6` → `AZWALL_RPC_TESTNET_V6`.
    pub fn rpc_env_key(&self) -> String {
        rpc_env_key(&self.id)
    }

    /// Major version of the Aztec client, which must match the node's.
    pub fn aztec_major(&self) -> Option<u64> {
        major_version(&self.aztec)
    }
}

pub fn rpc_env_key(network_id: &str) -> String {
    format!("AZWALL_RPC_{}", network_id.to_ascii_uppercase().replace('-', "_"))
}

/// `6.0.0-rc.3` → 6. Accepts a leading `v`.
pub fn major_version(version: &str) -> Option<u64> {
    version.trim_start_matches('v').split('.').next()?.parse().ok()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Registry {
    pub schema: u32,
    pub default: String,
    pub networks: Vec<NetworkSpec>,
}

impl Registry {
    pub fn from_json(json: &str) -> Result<Self> {
        let registry: Registry = serde_json::from_str(json).map_err(|e| CoreError::Registry(e.to_string()))?;
        registry.validate()?;
        Ok(registry)
    }

    pub fn get(&self, id: &str) -> Result<&NetworkSpec> {
        self.networks.iter().find(|n| n.id == id).ok_or_else(|| CoreError::UnknownNetwork(id.to_string()))
    }

    /// Every Barretenberg release the registry needs, deduplicated and sorted.
    pub fn barretenberg_releases(&self) -> BTreeSet<&str> {
        self.networks.iter().map(|n| n.barretenberg.as_str()).collect()
    }

    fn validate(&self) -> Result<()> {
        if self.schema != REGISTRY_SCHEMA {
            return Err(CoreError::Registry(format!("schema {} is not supported (expected {REGISTRY_SCHEMA})", self.schema)));
        }
        if self.networks.is_empty() {
            return Err(CoreError::Registry("no networks".into()));
        }
        let mut ids = HashSet::new();
        for network in &self.networks {
            validate_network(network)?;
            if !ids.insert(network.id.as_str()) {
                return Err(CoreError::Registry(format!("duplicate network id '{}'", network.id)));
            }
        }
        if !ids.contains(self.default.as_str()) {
            return Err(CoreError::Registry(format!("default network '{}' is not in the registry", self.default)));
        }
        Ok(())
    }
}

fn validate_network(n: &NetworkSpec) -> Result<()> {
    let bad = |what: &str| Err(CoreError::Registry(format!("network '{}': {what}", n.id)));
    let id_ok = !n.id.is_empty() && n.id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
    if !id_ok {
        return bad("id must be non-empty and use only a-z, 0-9 and '-'");
    }
    if n.label.trim().is_empty() {
        return bad("empty label");
    }
    if major_version(&n.aztec).is_none() {
        return bad("aztec version must start with a numeric major");
    }
    if major_version(&n.barretenberg).is_none() {
        return bad("barretenberg version must start with a numeric major");
    }
    if let Some(url) = &n.default_rpc {
        if !is_http_url(url) {
            return bad("defaultRpc must be an http(s) URL");
        }
    }
    Ok(())
}

pub fn is_http_url(url: &str) -> bool {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"));
    rest.is_some_and(|r| !r.is_empty() && !r.starts_with('/'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_json() -> String {
        r#"{
          "schema": 1,
          "default": "net-a",
          "networks": [
            { "id": "net-a", "label": "A", "aztec": "6.0.0-rc.3", "barretenberg": "6.0.0-rc.6",
              "defaultRpc": "https://a.example", "expected": { "l1ChainId": 1, "rollupVersion": 10 } },
            { "id": "net-b", "label": "B", "aztec": "5.1.0", "barretenberg": "5.1.0",
              "expected": { "l1ChainId": 1, "rollupVersion": 20 } }
          ]
        }"#
        .to_string()
    }

    #[test]
    fn parses_a_valid_registry() {
        let r = Registry::from_json(&sample_json()).unwrap();
        assert_eq!(r.networks.len(), 2);
        assert_eq!(r.get("net-b").unwrap().expected.rollup_version, 20);
        assert_eq!(r.barretenberg_releases().into_iter().collect::<Vec<_>>(), ["5.1.0", "6.0.0-rc.6"]);
    }

    #[test]
    fn rejects_duplicate_ids() {
        let json = sample_json().replace("\"net-b\"", "\"net-a\"");
        assert!(matches!(Registry::from_json(&json), Err(CoreError::Registry(m)) if m.contains("duplicate")));
    }

    #[test]
    fn rejects_unknown_default() {
        let json = sample_json().replace("\"default\": \"net-a\"", "\"default\": \"nope\"");
        assert!(Registry::from_json(&json).is_err());
    }

    #[test]
    fn rejects_bad_ids_versions_and_urls() {
        for (from, to) in [("\"net-a\",", "\"Net A\","), ("6.0.0-rc.3", "next"), ("https://a.example", "ftp://a")] {
            let json = sample_json().replace(from, to);
            assert!(Registry::from_json(&json).is_err(), "accepted {to}");
        }
    }

    #[test]
    fn rejects_other_schemas() {
        assert!(Registry::from_json(&sample_json().replace("\"schema\": 1", "\"schema\": 2")).is_err());
    }

    #[test]
    fn unknown_network_is_an_error() {
        let r = Registry::from_json(&sample_json()).unwrap();
        assert!(matches!(r.get("mainnet"), Err(CoreError::UnknownNetwork(_))));
    }

    #[test]
    fn env_keys_and_majors() {
        assert_eq!(rpc_env_key("testnet-v6"), "AZWALL_RPC_TESTNET_V6");
        assert_eq!(major_version("6.0.0-rc.3"), Some(6));
        assert_eq!(major_version("v5.1.0"), Some(5));
        assert_eq!(major_version("x"), None);
    }

    #[test]
    fn http_urls() {
        assert!(is_http_url("https://aztec-testnet.drpc.org"));
        assert!(is_http_url("http://localhost:8080"));
        assert!(!is_http_url("https://"));
        assert!(!is_http_url("wss://node"));
    }
}
