//! Keeps one Barretenberg release loaded at a time and switches when a request needs another.
//! Each release costs its library plus its own CRS copy in memory, and the wallet uses a single
//! network at a time, so the previous release is unloaded before the next one loads.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::backend::ChonkBackend;
use crate::bindings;
use crate::engine::{self, ProverEngine};
use crate::library::{self, BbLibrary};
use crate::srs::Srs;
use crate::{ProverError, Result};

/// Opens a ready-to-prove backend for a release. Runs on the release's prover thread.
pub type BackendFactory = Arc<dyn Fn(&str) -> Result<Box<dyn ChonkBackend>> + Send + Sync>;

pub struct ProverManager {
    factory: BackendFactory,
    active: Mutex<Option<ProverEngine>>,
}

impl ProverManager {
    /// The production manager: releases are loaded from `lib_dir` (see [`library::file_name`])
    /// and initialised with the CRS files in `crs_dir`. `threads = 0` uses every core.
    pub fn native(lib_dir: PathBuf, crs_dir: PathBuf, threads: usize) -> Result<Self> {
        engine::configure_threads(threads)?;
        let factory: BackendFactory = Arc::new(move |release: &str| {
            if bindings::binding_for(release).is_none() {
                return Err(ProverError::UnsupportedRelease(release.to_string()));
            }
            let library = BbLibrary::load(&library::path_in(&lib_dir, release))?;
            let mut backend = bindings::open(release, library)?;
            backend.init_srs(&Srs::load(&crs_dir)?)?;
            Ok(backend)
        });
        Ok(Self::with_factory(factory))
    }

    pub fn with_factory(factory: BackendFactory) -> Self {
        Self { factory, active: Mutex::new(None) }
    }

    pub fn active_release(&self) -> Option<String> {
        self.lock().as_ref().map(|e| e.release().to_string())
    }

    /// Loads `release` if it is not the active one (unloading the previous release first).
    pub fn activate(&self, release: &str) -> Result<()> {
        let mut active = self.lock();
        self.ensure(&mut active, release)
    }

    /// Proves a [`crate::wire`] request with `release`, switching releases if needed.
    pub fn prove(&self, release: &str, request: Vec<u8>) -> Result<Vec<u8>> {
        let mut active = self.lock();
        self.ensure(&mut active, release)?;
        active.as_ref().expect("ensured above").prove(request)
    }

    pub fn unload(&self) {
        self.lock().take();
    }

    fn ensure(&self, active: &mut Option<ProverEngine>, release: &str) -> Result<()> {
        if active.as_ref().is_some_and(|e| e.release() == release) {
            return Ok(());
        }
        drop(active.take());
        let factory = Arc::clone(&self.factory);
        let owned = release.to_string();
        *active = Some(ProverEngine::spawn(release, move || factory(&owned))?);
        log::info!("prover: Barretenberg {release} loaded");
        Ok(())
    }

    fn lock(&self) -> MutexGuard<'_, Option<ProverEngine>> {
        // A panic while proving leaves no partial state behind worth refusing to reuse.
        self.active.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}
