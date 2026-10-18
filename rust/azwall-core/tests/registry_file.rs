//! Checks the repository's real `networks.json` and `.env.example`.

use azwall_core::{dotenv, AppConfig, Registry};

const REGISTRY: &str = include_str!("../../../networks.json");
const ENV_EXAMPLE: &str = include_str!("../../../.env.example");

#[test]
fn the_shipped_registry_is_valid() {
    let registry = Registry::from_json(REGISTRY).unwrap();
    assert!(registry.networks.len() >= 2, "the app is expected to embed several Aztec versions");
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
