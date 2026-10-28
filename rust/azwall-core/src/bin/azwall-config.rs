//! Resolves `networks.json` + `.env` into the config JSON both apps bundle.
//!
//! Usage: azwall-config [--registry networks.json] [--env .env] [--out assets/azwall-config.json] [--public-only]
//! Without `--out`, prints to stdout. Paths default to the repository root. `--public-only` (release builds) fails
//! when `.env` replaced a registry endpoint: one's own RPC URL may carry its API key.

use std::path::{Path, PathBuf};
use std::process::ExitCode;

use azwall_core::{dotenv, AppConfig, Registry};

struct Args {
    registry: PathBuf,
    env: PathBuf,
    out: Option<PathBuf>,
    public_only: bool,
}

fn parse_args() -> Result<Args, String> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let mut args = Args { registry: root.join("networks.json"), env: root.join(".env"), out: None, public_only: false };
    let mut it = std::env::args().skip(1);
    while let Some(flag) = it.next() {
        if flag == "--public-only" {
            args.public_only = true;
            continue;
        }
        let value = it.next().map(PathBuf::from).ok_or_else(|| format!("{flag} needs a value"))?;
        match flag.as_str() {
            "--registry" => args.registry = value,
            "--env" => args.env = value,
            "--out" => args.out = Some(value),
            _ => return Err(format!("unknown flag {flag}")),
        }
    }
    Ok(args)
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    let json = std::fs::read_to_string(&args.registry).map_err(|e| format!("{}: {e}", args.registry.display()))?;
    let registry = Registry::from_json(&json).map_err(|e| e.to_string())?;
    let env = dotenv::load(&args.env).map_err(|e| e.to_string())?;
    let config = AppConfig::resolve(&registry, &env).map_err(|e| e.to_string())?;
    let private = config.endpoints_from_env();
    if args.public_only && !private.is_empty() {
        return Err(format!("endpoints from .env ({}): a release ships the registry's public ones only; clear them in .env", private.join(", ")));
    }
    for id in &config.unavailable {
        eprintln!("warning: network '{id}' has no RPC endpoint and is left out");
    }
    match args.out {
        None => println!("{}", config.to_json()),
        Some(out) => {
            if let Some(dir) = out.parent() {
                std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
            }
            std::fs::write(&out, config.to_json()).map_err(|e| format!("{}: {e}", out.display()))?;
            eprintln!("wrote {} ({} networks)", out.display(), config.networks.len());
        }
    }
    Ok(())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("azwall-config: {e}");
            ExitCode::FAILURE
        }
    }
}
