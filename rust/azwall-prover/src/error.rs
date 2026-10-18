#[derive(Debug, thiserror::Error)]
pub enum ProverError {
    #[error("Barretenberg {release}: {message}")]
    Barretenberg { release: String, message: String },
    #[error("no Barretenberg binding for release {0} in this build")]
    UnsupportedRelease(String),
    #[error("cannot load {path}: {message}")]
    Library { path: String, message: String },
    #[error("malformed prover message: {0}")]
    Wire(String),
    #[error("CRS: {0}")]
    Srs(String),
    #[error("prover thread: {0}")]
    Thread(String),
    #[error("the prover already runs with {running} threads in this process; restart the app to use {requested}")]
    ThreadsFixed { running: usize, requested: usize },
}

impl ProverError {
    pub fn bb(release: &str, message: impl Into<String>) -> Self {
        Self::Barretenberg { release: release.to_string(), message: message.into() }
    }
}
