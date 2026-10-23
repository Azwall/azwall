//! Checks the repository's real `networks.json` and `.env.example`.

use azwall_core::{dotenv, AppConfig, Registry};

const REGISTRY: &str = include_str!("../../../networks.json");
const ENV_EXAMPLE: &str = include_str!("../../../.env.example");

#[test]
fn the_shipped_registry_is_valid() {
    let registry = Registry::from_json(REGISTRY).unwrap();
    // One version today; the several-versions behaviour is covered by the registry's unit fixtures.
    assert!(!registry.networks.is_empty(), "the app is expected to embed at least one Aztec version");
}

#[test]
fn env_example_declares_a_key_for_every_network() {
    let registry = Registry::from_json(REGISTRY).unwrap();
    let env = dotenv::parse(ENV_EXAMPLE).unwrap();
    for network in &registry.networks {
        assert!(env.contains_key(&network.rpc_env_key()), "{} missing from .env.example", network.rpc_env_key());
    }
}

#[test]
fn the_example_env_resolves_every_network() {
    let registry = Registry::from_json(REGISTRY).unwrap();
    let config = AppConfig::resolve(&registry, &dotenv::parse(ENV_EXAMPLE).unwrap()).unwrap();
    assert!(config.unavailable.is_empty(), "networks without an RPC: {:?}", config.unavailable);
}

/// Every network's PXE bundle exists and pins its Aztec packages to the network's versions.
#[test]
fn every_network_has_a_matching_pxe_bundle() {
    let registry = Registry::from_json(REGISTRY).unwrap();
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../pxe");
    for network in &registry.networks {
        let manifest = root.join(&network.pxe).join("package.json");
        let text = std::fs::read_to_string(&manifest).unwrap_or_else(|e| panic!("{}: {e}", manifest.display()));
        let package: serde_json::Value = serde_json::from_str(&text).unwrap();
        let aztec: Vec<(&String, &serde_json::Value)> =
            package["dependencies"].as_object().unwrap().iter().filter(|(name, _)| name.starts_with("@aztec")).collect();
        assert!(aztec.iter().any(|(_, v)| *v == &network.aztec), "{}: no package at Aztec {}", network.id, network.aztec);
        for (name, version) in aztec {
            let ok = version == &network.aztec || version == &network.barretenberg;
            assert!(ok, "{}: {name}@{version} matches neither {} nor {}", network.id, network.aztec, network.barretenberg);
        }
    }
}
