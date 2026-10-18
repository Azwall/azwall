use super::*;

/// Records the command sequence instead of talking to Barretenberg.
#[derive(Default)]
struct Script {
    calls: Vec<String>,
    valid: bool,
    fail_on: Option<&'static str>,
}

impl Script {
    fn step(&mut self, command: &str) -> std::result::Result<(), String> {
        self.calls.push(command.to_string());
        match self.fail_on {
            Some(c) if command.starts_with(c) => Err("boom".into()),
            _ => Ok(()),
        }
    }
}

impl ChonkApi for Script {
    type Proof = Vec<Vec<u8>>;
    fn init_srs(&mut self, _srs: &Srs) -> std::result::Result<(), String> {
        self.step("srs")
    }
    fn start(&mut self, kinds: Vec<u32>) -> std::result::Result<(), String> {
        self.step(&format!("start {kinds:?}"))
    }
    fn load(&mut self, step: &mut ExecutionStep) -> std::result::Result<(), String> {
        self.step(&format!("load {}", step.name))
    }
    fn accumulate(&mut self, _witness: &[u8]) -> std::result::Result<(), String> {
        self.step("accumulate")
    }
    fn prove(&mut self) -> std::result::Result<Self::Proof, String> {
        self.step("prove").map(|_| vec![vec![1; 32], vec![2; 32]])
    }
    fn verify(&mut self, _proof: Self::Proof, vk: &[u8]) -> std::result::Result<bool, String> {
        self.step(&format!("verify vk={}", vk[0])).map(|_| self.valid)
    }
    fn compress(&mut self, _proof: Self::Proof) -> std::result::Result<Vec<u8>, String> {
        self.step("compress").map(|_| b"small".to_vec())
    }
    fn fields(proof: Self::Proof) -> Vec<Vec<u8>> {
        proof
    }
}

fn steps() -> Vec<ExecutionStep> {
    (0..2)
        .map(|i| ExecutionStep { kind: 10 + i, name: format!("c{i}"), bytecode: vec![1], witness: vec![2], vk: vec![i as u8 + 7] })
        .collect()
}

fn backend(script: Script) -> ReleaseBackend<Script> {
    ReleaseBackend::new("9.9.9", script)
}

#[test]
fn runs_the_chonk_sequence_and_verifies_with_the_last_vk() {
    let mut b = backend(Script { valid: true, ..Default::default() });
    let out = b.prove(steps()).unwrap();
    assert_eq!(out.fields, [vec![1; 32], vec![2; 32]]);
    assert_eq!(out.compressed, b"small");
    let expected = ["start [10, 11]", "load c0", "accumulate", "load c1", "accumulate", "prove", "verify vk=8", "compress"];
    assert_eq!(b.api.calls, expected);
}

#[test]
fn an_invalid_proof_is_an_error() {
    let mut b = backend(Script { valid: false, ..Default::default() });
    assert!(b.prove(steps()).unwrap_err().to_string().contains("does not verify"));
}

#[test]
fn errors_name_the_release_and_command() {
    let mut b = backend(Script { valid: true, fail_on: Some("load c1"), ..Default::default() });
    let message = b.prove(steps()).unwrap_err().to_string();
    assert!(message.contains("9.9.9") && message.contains("ChonkLoad c1"), "{message}");
}

#[test]
fn missing_vks_and_empty_requests_are_rejected() {
    let mut no_vk = steps();
    no_vk[0].vk.clear();
    assert!(backend(Script::default()).prove(no_vk).is_err());
    assert!(backend(Script::default()).prove(Vec::new()).is_err());
}

#[test]
fn release_table() {
    assert_eq!(binding_for("does-not-exist"), None);
    #[cfg(feature = "bb-v5")]
    assert_eq!(binding_for("5.1.0"), Some(Binding::V5));
    #[cfg(feature = "bb-v6")]
    assert_eq!(binding_for("6.0.0-rc.6"), Some(Binding::V6));
    let releases = supported_releases();
    let unique: std::collections::BTreeSet<_> = releases.iter().collect();
    assert_eq!(unique.len(), releases.len(), "a release is listed twice");
}
