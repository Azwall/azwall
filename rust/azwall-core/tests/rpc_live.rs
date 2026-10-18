//! Live checks against the RPC endpoints configured in `.env` (or the registry defaults).
//! They need the network, so they are ignored by default:
//!
//!     cargo test -p azwall-core --test rpc_live -- --ignored --nocapture

use std::path::Path;

use azwall_core::rpc::{check_compatibility, HttpTransport, NodeClient};
use azwall_core::{dotenv, AppConfig, Registry};

fn config() -> AppConfig {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let registry = Registry::from_json(&std::fs::read_to_string(root.join("networks.json")).unwrap()).unwrap();
    AppConfig::resolve(&registry, &dotenv::load(&root.join(".env")).unwrap()).unwrap()
}

#[test]
#[ignore = "needs network access to the configured Aztec nodes"]
fn every_configured_node_answers_and_matches_its_network() {
    let mut failures = Vec::new();
    for network in config().networks {
        let client = NodeClient::new(HttpTransport::default(), network.rpc_url.clone());
        match client.node_info() {
            Ok(info) => {
                let compat = check_compatibility(&network.spec, info);
                println!("{}: node {} → {:?}", network.spec.id, compat.node.node_version, compat.mismatches);
                if !compat.is_compatible() {
                    failures.push(format!("{}: {}", network.spec.id, compat.mismatches.join(", ")));
                }
            }
            Err(e) => failures.push(format!("{}: {e}", network.spec.id)),
        }
    }
    assert!(failures.is_empty(), "{failures:#?}");
}
