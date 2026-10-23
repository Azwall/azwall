use super::*;
use azwall_core::{dotenv, Registry};

fn config_json() -> String {
    let registry = Registry::from_json(include_str!("../../../networks.json")).unwrap();
    let env = dotenv::parse(include_str!("../../../.env.example")).unwrap();
    AppConfig::resolve(&registry, &env).unwrap().to_json()
}

fn core() -> Arc<WalletCore> {
    let tmp = std::env::temp_dir().to_string_lossy().into_owned();
    WalletCore::new(config_json(), tmp.clone(), tmp, 1).unwrap()
}

#[test]
fn lists_the_bundled_networks() {
    let core = core();
    let networks = core.networks();
    assert!(networks.iter().any(|n| n.id == core.default_network_id()));
    assert!(networks.iter().all(|n| n.prover_supported), "{networks:?}");
}

#[test]
fn rejects_invalid_config() {
    let err = WalletCore::new("{}".into(), String::new(), String::new(), 1).err().unwrap();
    assert!(matches!(err, AzwallError::Config { .. }));
}

#[test]
fn unknown_networks_are_reported() {
    let err = core().prove("nope".into(), Vec::new()).unwrap_err();
    assert_eq!(err, AzwallError::UnknownNetwork { id: "nope".into() });
}

#[test]
fn proving_without_libraries_is_a_prover_error() {
    let core = core();
    let id = core.default_network_id();
    assert!(matches!(core.prepare_prover(id), Err(AzwallError::Prover { .. })));
    assert_eq!(core.active_prover_release(), None);
}

#[test]
fn exposes_the_supported_releases() {
    assert!(supported_barretenberg_releases().contains(&"6.0.0".to_string()));
    assert_eq!(core_version(), env!("CARGO_PKG_VERSION"));
}

impl PartialEq for AzwallError {
    fn eq(&self, other: &Self) -> bool {
        self.to_string() == other.to_string()
    }
}

#[test]
fn exposes_pxe_bundles_and_open_params() {
    let core = core();
    let id = core.default_network_id();
    let network = core.networks().into_iter().find(|n| n.id == id).unwrap();
    assert!(!network.pxe_bundle.is_empty());
    let params: serde_json::Value = serde_json::from_str(&core.pxe_open_params(id.clone()).unwrap()).unwrap();
    assert_eq!(params["id"], id.as_str());
    assert!(params["expected"]["l1ChainId"].is_u64());
}
