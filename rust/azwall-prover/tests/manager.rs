//! Release switching and the request → response path, with a fake Barretenberg backend.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use azwall_prover::manager::BackendFactory;
use azwall_prover::wire::{self, ExecutionStep};
use azwall_prover::{ChonkBackend, ChonkOutput, ProveTimings, ProverError, ProverManager, Srs};

/// Logs which release was opened, used and dropped.
struct Fake {
    release: String,
    log: Arc<Mutex<Vec<String>>>,
}

impl ChonkBackend for Fake {
    fn release(&self) -> &str {
        &self.release
    }
    fn init_srs(&mut self, _srs: &Srs) -> azwall_prover::Result<()> {
        Ok(())
    }
    fn prove(&mut self, steps: Vec<ExecutionStep>) -> azwall_prover::Result<ChonkOutput> {
        self.log.lock().unwrap().push(format!("prove {} x{}", self.release, steps.len()));
        let tag = self.release.as_bytes()[0];
        Ok(ChonkOutput { fields: vec![vec![tag; 32]], compressed: vec![tag], timings: ProveTimings::default() })
    }
}

impl Drop for Fake {
    fn drop(&mut self) {
        self.log.lock().unwrap().push(format!("drop {}", self.release));
    }
}

fn manager() -> (ProverManager, Arc<Mutex<Vec<String>>>, Arc<AtomicUsize>) {
    let log = Arc::new(Mutex::new(Vec::new()));
    let opened = Arc::new(AtomicUsize::new(0));
    let (l, o) = (log.clone(), opened.clone());
    let factory: BackendFactory = Arc::new(move |release: &str| {
        if release == "broken" {
            return Err(ProverError::UnsupportedRelease(release.into()));
        }
        o.fetch_add(1, Ordering::SeqCst);
        l.lock().unwrap().push(format!("open {release}"));
        Ok(Box::new(Fake { release: release.into(), log: l.clone() }) as Box<dyn ChonkBackend>)
    });
    (ProverManager::with_factory(factory), log, opened)
}

fn request() -> Vec<u8> {
    let step = |i: u32| ExecutionStep { kind: i, name: format!("c{i}"), bytecode: vec![1; 10], witness: vec![2; 10], vk: vec![3] };
    wire::encode_request(&[step(0), step(1)]).unwrap()
}

#[test]
fn proves_and_frames_the_response() {
    let (m, _, _) = manager();
    let response = wire::decode_response(&m.prove("6.0.0", request()).unwrap()).unwrap();
    assert_eq!(response.fields, [[b'6'; 32]]);
    let meta: serde_json::Value = serde_json::from_str(&response.meta_json).unwrap();
    assert_eq!(meta["release"], "6.0.0");
    assert_eq!(meta["circuits"], serde_json::json!(["c0", "c1"]));
}

#[test]
fn reuses_the_active_release() {
    let (m, _, opened) = manager();
    m.prove("6.0.0", request()).unwrap();
    m.prove("6.0.0", request()).unwrap();
    assert_eq!(opened.load(Ordering::SeqCst), 1);
    assert_eq!(m.active_release().as_deref(), Some("6.0.0"));
}

#[test]
fn unloads_the_previous_release_before_loading_the_next() {
    let (m, log, _) = manager();
    m.prove("5.1.0", request()).unwrap();
    m.prove("6.0.0", request()).unwrap();
    m.unload();
    let log = log.lock().unwrap().clone();
    assert_eq!(log, ["open 5.1.0", "prove 5.1.0 x2", "drop 5.1.0", "open 6.0.0", "prove 6.0.0 x2", "drop 6.0.0"]);
    assert_eq!(m.active_release(), None);
}

#[test]
fn a_failed_load_leaves_nothing_active() {
    let (m, _, _) = manager();
    m.activate("6.0.0").unwrap();
    assert!(matches!(m.prove("broken", request()), Err(ProverError::UnsupportedRelease(_))));
    assert_eq!(m.active_release(), None);
}

#[test]
fn malformed_requests_do_not_kill_the_engine() {
    let (m, _, opened) = manager();
    assert!(matches!(m.prove("6.0.0", b"nope".to_vec()), Err(ProverError::Wire(_))));
    m.prove("6.0.0", request()).unwrap();
    assert_eq!(opened.load(Ordering::SeqCst), 1);
}

#[test]
fn native_manager_reports_missing_libraries() {
    let dir = std::env::temp_dir();
    let m = ProverManager::native(dir.clone(), dir, 1).unwrap();
    let err = m.activate("6.0.0-rc.6").unwrap_err();
    assert!(matches!(err, ProverError::Library { .. }), "{err}");
    assert!(matches!(m.activate("0.0.1").unwrap_err(), ProverError::UnsupportedRelease(_)));
}
