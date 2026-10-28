//! The registry of Aztec networks the app supports (`networks.json` at the repository root).
//!
//! Aztec runs several incompatible versions at once (a testnet, mainnet alpha…). Each
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

/// Who pays a network's fees. Declared in the registry, never inferred from the chain: anyone can deploy a contract at
/// the Sponsored FPC's deterministic address, and an unfunded one would make every transaction fail.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FeeMode {
    /// The network's Sponsored FPC pays (test networks).
    Sponsored,
    /// The account pays with its own Fee Juice, bridged from Ethereum as AZTEC (mainnet). The default: a network
    /// that does not say otherwise has no sponsor.
    #[default]
    FeeJuice,
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
    /// PXE host bundle (`pxe/<bundle>/`, built into `assets/pxe/<bundle>/`) running this Aztec version.
    pub pxe: String,
    /// Public endpoint used when `.env` sets none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default_rpc: Option<String>,
    pub expected: Expected,
    #[serde(default)]
    pub fees: FeeMode,
    /// Tokens every wallet follows on this network from the start (it may stop following them).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tokens: Vec<KnownToken>,
    /// dApps the browser's start page suggests on this network: a dApp works with one Aztec version only.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub dapps: Vec<KnownDapp>,
    /// Block explorers following this network's chain: an explorer indexes one network (and Aztec version) at a time.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub explorers: Vec<KnownExplorer>,
    /// The network's Ethereum side, where the wallet finds Fee Juice bridged to its accounts.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub l1: Option<L1Spec>,
}

/// Where a network's Ethereum side is read: the deposits its Fee Juice portal received for an address.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct L1Spec {
    /// Public Ethereum JSON-RPC endpoint used when `.env` sets none.
    pub rpc: String,
    /// Tried when `rpc` fails or refuses the request: public endpoints change their limits without notice.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fallback_rpc: Option<String>,
    /// The network's Fee Juice portal on Ethereum: pinned, so a node cannot point the wallet at another contract.
    pub portal: String,
    /// The block the network's Fee Juice portal was deployed in: no deposit for it exists before.
    pub portal_from_block: u64,
}

/// A token a network's registry entry lists: what the wallet needs to show it before asking the chain.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KnownToken {
    pub address: String,
    pub name: String,
    pub symbol: String,
    pub decimals: u8,
}

/// A site the browser's start page lists, with a line on what it is for.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KnownDapp {
    pub name: String,
    pub url: String,
    #[serde(default)]
    pub description: String,
}

/// A block explorer of a network: its home page, and where it shows one transaction.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KnownExplorer {
    pub name: String,
    pub url: String,
    /// A transaction's page, `{hash}` standing for its hash: `https://aztecscan.xyz/tx-effects/{hash}`.
    pub tx: String,
}

impl NetworkSpec {
    /// The `.env` key holding this network's RPC URL: `testnet-v6` → `AZWALL_RPC_TESTNET_V6`.
    pub fn rpc_env_key(&self) -> String {
        rpc_env_key(&self.id)
    }

    /// The `.env` key holding this network's Ethereum RPC URL: `testnet-v6` → `AZWALL_L1_RPC_TESTNET_V6`.
    pub fn l1_rpc_env_key(&self) -> String {
        format!("AZWALL_L1_RPC_{}", self.id.to_ascii_uppercase().replace('-', "_"))
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
    if n.pxe.is_empty() || !n.pxe.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit()) {
        return bad("pxe must name a bundle directory (a-z, 0-9)");
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
    for t in &n.tokens {
        let address_ok = t.address.len() == 66 && t.address.starts_with("0x") && t.address[2..].chars().all(|c| c.is_ascii_hexdigit());
        if !address_ok || t.symbol.trim().is_empty() || t.name.trim().is_empty() || t.decimals > 36 {
            return bad(&format!("token '{}' needs a 0x + 64 hex address, a name, a symbol and at most 36 decimals", t.symbol));
        }
    }
    if n.l1.as_ref().is_some_and(|l1| !is_http_url(&l1.rpc) || l1.fallback_rpc.as_deref().is_some_and(|u| !is_http_url(u))) {
        return bad("l1.rpc and l1.fallbackRpc must be http(s) URLs");
    }
    if n.l1.as_ref().is_some_and(|l1| !(l1.portal.len() == 42 && l1.portal.starts_with("0x") && l1.portal[2..].chars().all(|c| c.is_ascii_hexdigit()))) {
        return bad("l1.portal must be an Ethereum address (0x + 40 hex)");
    }
    for d in &n.dapps {
        if d.name.trim().is_empty() || !d.url.starts_with("https://") || !is_http_url(&d.url) {
            return bad(&format!("dApp '{}' needs a name and an https URL", d.name));
        }
    }
    for e in &n.explorers {
        let https = |url: &str| url.starts_with("https://") && is_http_url(url);
        if e.name.trim().is_empty() || !https(&e.url) || !https(&e.tx) || e.tx.matches("{hash}").count() != 1 {
            return bad(&format!("explorer '{}' needs a name, an https URL and an https tx URL with one {{hash}}", e.name));
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
            { "id": "net-a", "label": "A", "aztec": "6.0.0-rc.3", "barretenberg": "6.0.0-rc.6", "pxe": "v6",
              "defaultRpc": "https://a.example", "expected": { "l1ChainId": 1, "rollupVersion": 10 } },
            { "id": "net-b", "label": "B", "aztec": "5.1.0", "barretenberg": "5.1.0", "pxe": "v5",
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
    fn lists_known_tokens_per_network() {
        let token = format!(r#""tokens": [{{ "address": "0x{}", "name": "Wrapped Ether", "symbol": "WETH", "decimals": 18 }}], "pxe": "v6""#, "ab".repeat(32));
        let r = Registry::from_json(&sample_json().replacen(r#""pxe": "v6""#, &token, 1)).unwrap();
        assert_eq!(r.networks[0].tokens[0].symbol, "WETH");
        assert!(r.networks[1].tokens.is_empty());
        let bad = sample_json().replacen(r#""pxe": "v6""#, r#""tokens": [{ "address": "0x12", "name": "X", "symbol": "X", "decimals": 6 }], "pxe": "v6""#, 1);
        assert!(Registry::from_json(&bad).is_err());
    }

    #[test]
    fn an_ethereum_side_takes_a_fallback_endpoint() {
        let l1 = |fallback: &str| format!(r#""l1": {{ "rpc": "https://eth.example", {fallback} "portal": "0x{}", "portalFromBlock": 7 }}, "pxe": "v6""#, "ab".repeat(20));
        let with = Registry::from_json(&sample_json().replacen(r#""pxe": "v6""#, &l1(r#""fallbackRpc": "https://eth2.example","#), 1)).unwrap();
        assert_eq!(with.networks[0].l1.as_ref().unwrap().fallback_rpc.as_deref(), Some("https://eth2.example"));
        let without = Registry::from_json(&sample_json().replacen(r#""pxe": "v6""#, &l1(""), 1)).unwrap();
        assert_eq!(without.networks[0].l1.as_ref().unwrap().fallback_rpc, None);
        assert!(Registry::from_json(&sample_json().replacen(r#""pxe": "v6""#, &l1(r#""fallbackRpc": "ftp://x","#), 1)).is_err());
        let short_portal = sample_json().replacen(r#""pxe": "v6""#, r#""l1": { "rpc": "https://eth.example", "portal": "0x12", "portalFromBlock": 7 }, "pxe": "v6""#, 1);
        assert!(Registry::from_json(&short_portal).is_err());
    }

    #[test]
    fn lists_dapps_per_network() {
        let dapps = r#""dapps": [{ "name": "Palmyr", "url": "https://aztec.palmyr.io", "description": "DeFi" }], "pxe": "v6""#;
        let r = Registry::from_json(&sample_json().replacen(r#""pxe": "v6""#, dapps, 1)).unwrap();
        assert_eq!(r.networks[0].dapps[0].url, "https://aztec.palmyr.io");
        assert!(r.networks[1].dapps.is_empty());
        for url in ["http://aztec.palmyr.io", "javascript:alert(1)", "https://"] {
            let bad = sample_json().replacen(r#""pxe": "v6""#, &dapps.replace("https://aztec.palmyr.io", url), 1);
            assert!(Registry::from_json(&bad).is_err(), "accepted {url}");
        }
    }

    #[test]
    fn lists_explorers_per_network() {
        let explorers = r#""explorers": [{ "name": "Aztecscan", "url": "https://aztecscan.xyz", "tx": "https://aztecscan.xyz/tx-effects/{hash}" }], "pxe": "v6""#;
        let r = Registry::from_json(&sample_json().replacen(r#""pxe": "v6""#, explorers, 1)).unwrap();
        assert_eq!(r.networks[0].explorers[0].tx, "https://aztecscan.xyz/tx-effects/{hash}");
        assert!(r.networks[1].explorers.is_empty());
        for (from, to) in [("/tx-effects/{hash}", "/tx-effects/"), ("https://aztecscan.xyz/tx", "http://aztecscan.xyz/tx"), ("\"https://aztecscan.xyz\"", "\"javascript:x\"")] {
            let bad = sample_json().replacen(r#""pxe": "v6""#, &explorers.replace(from, to), 1);
            assert!(Registry::from_json(&bad).is_err(), "accepted {to}");
        }
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
        for (from, to) in [("\"net-a\",", "\"Net A\","), ("6.0.0-rc.3", "next"), ("https://a.example", "ftp://a"), ("\"v6\"", "\"../x\"")] {
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
