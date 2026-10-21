#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error("invalid network registry: {0}")]
    Registry(String),
    #[error("invalid .env at line {line}: {reason}")]
    Dotenv { line: usize, reason: String },
    #[error("invalid configuration: {0}")]
    Config(String),
    #[error("unknown network '{0}'")]
    UnknownNetwork(String),
    #[error("RPC: {0}")]
    Rpc(String),
    /// A recovery phrase problem, worded for the user.
    #[error("{0}")]
    Phrase(String),
    #[error("I/O: {0}")]
    Io(#[from] std::io::Error),
}
