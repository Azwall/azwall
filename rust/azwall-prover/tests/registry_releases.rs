//! Every network in `networks.json` must have a Barretenberg binding in this build, otherwise the
//! app would list a network it cannot prove for.

use azwall_core::Registry;
use azwall_prover::bindings;

#[test]
fn every_registry_release_has_a_binding() {
    let registry = Registry::from_json(include_str!("../../../networks.json")).unwrap();
    for network in &registry.networks {
        assert!(
            bindings::binding_for(&network.barretenberg).is_some(),
            "network '{}' needs Barretenberg {} but this build supports {:?}",
            network.id,
            network.barretenberg,
            bindings::supported_releases()
        );
    }
}
