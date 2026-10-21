//! A dedicated prover thread owning one backend. Barretenberg keeps per-thread state and wants a
//! large stack, so every command for a release runs on the thread that opened it.

use std::sync::{mpsc, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::Instant;

use serde::Serialize;

use crate::backend::{ChonkBackend, ProveTimings};
use crate::wire;
use crate::{ProverError, Result};

const STACK_SIZE: usize = 16 * 1024 * 1024;

type Reply = mpsc::Sender<Result<Vec<u8>>>;

pub struct ProverEngine {
    release: String,
    jobs: Option<mpsc::Sender<(Vec<u8>, Reply)>>,
    thread: Option<JoinHandle<()>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProveMeta<'a> {
    release: &'a str,
    circuits: Vec<String>,
    decode_ms: f64,
    total_ms: f64,
    timings: ProveTimings,
}

impl ProverEngine {
    /// Starts the thread and runs `open` on it (load the library, init the CRS). Returns once
    /// the backend is ready, or with the error that prevented it.
    pub fn spawn<F>(release: &str, open: F) -> Result<Self>
    where
        F: FnOnce() -> Result<Box<dyn ChonkBackend>> + Send + 'static,
    {
        let (jobs, rx) = mpsc::channel::<(Vec<u8>, Reply)>();
        let (ready_tx, ready_rx) = mpsc::channel::<Result<()>>();
        let thread = thread::Builder::new()
            .name(format!("bb-{release}"))
            .stack_size(STACK_SIZE)
            .spawn(move || {
                raise_priority();
                let mut backend = match open() {
                    Ok(b) => b,
                    Err(e) => return drop(ready_tx.send(Err(e))),
                };
                let _ = ready_tx.send(Ok(()));
                for (request, reply) in rx {
                    let _ = reply.send(prove(backend.as_mut(), &request));
                }
            })
            .map_err(|e| ProverError::Thread(e.to_string()))?;
        let ready = ready_rx.recv().map_err(|_| ProverError::Thread("prover thread died while starting".into()));
        let engine = Self { release: release.to_string(), jobs: Some(jobs), thread: Some(thread) };
        ready??;
        Ok(engine)
    }

    pub fn release(&self) -> &str {
        &self.release
    }

    /// Proves a request framed by [`wire`] and returns the framed response.
    pub fn prove(&self, request: Vec<u8>) -> Result<Vec<u8>> {
        let (tx, rx) = mpsc::channel();
        let jobs = self.jobs.as_ref().ok_or_else(|| ProverError::Thread("engine is shut down".into()))?;
        jobs.send((request, tx)).map_err(|_| ProverError::Thread("prover thread is gone".into()))?;
        rx.recv().map_err(|_| ProverError::Thread("prover thread died while proving".into()))?
    }
}

impl Drop for ProverEngine {
    /// Waits for the thread so the release's library and CRS are freed before another loads.
    fn drop(&mut self) {
        drop(self.jobs.take());
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn prove(backend: &mut dyn ChonkBackend, request: &[u8]) -> Result<Vec<u8>> {
    let total = Instant::now();
    let steps = wire::decode_request(request)?;
    let decode_ms = total.elapsed().as_secs_f64() * 1000.0;
    let circuits = steps.iter().map(|s| s.name.clone()).collect();
    let output = backend.prove(steps)?;
    let meta = ProveMeta {
        release: backend.release(),
        circuits,
        decode_ms,
        total_ms: total.elapsed().as_secs_f64() * 1000.0,
        timings: output.timings,
    };
    let meta_json = serde_json::to_string(&meta).map_err(|e| ProverError::Wire(e.to_string()))?;
    wire::encode_response(&output.fields, &output.compressed, &meta_json)
}

/// On Apple platforms, runs the prover at user-initiated QoS. The app waits for a proof from a user-initiated task,
/// and a default-QoS thread there is a priority inversion, which iOS may answer by running it on efficiency cores.
/// Barretenberg starts its thread pool from this thread, whose class the pool's threads inherit.
fn raise_priority() {
    #[cfg(target_vendor = "apple")]
    {
        const QOS_CLASS_USER_INITIATED: u32 = 0x19;
        extern "C" {
            fn pthread_set_qos_class_self_np(qos_class: u32, relative_priority: i32) -> i32;
        }
        // SAFETY: changes only the calling thread's scheduling class; no memory is involved.
        unsafe { pthread_set_qos_class_self_np(QOS_CLASS_USER_INITIATED, 0) };
    }
}

static PROCESS_THREADS: OnceLock<usize> = OnceLock::new();

/// Barretenberg reads its thread count once (`HARDWARE_CONCURRENCY`) and sizes a process-wide
/// pool, so the count is fixed for the life of the process. `0` means all cores.
pub fn configure_threads(requested: usize) -> Result<usize> {
    let available = thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let threads = if requested == 0 { available } else { requested.min(available) };
    let fixed = *PROCESS_THREADS.get_or_init(|| {
        // Before any Barretenberg code runs; nothing else in the process reads this variable.
        std::env::set_var("HARDWARE_CONCURRENCY", threads.to_string());
        threads
    });
    if fixed != threads {
        return Err(ProverError::ThreadsFixed { running: fixed, requested: threads });
    }
    Ok(fixed)
}
