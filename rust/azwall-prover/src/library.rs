//! One Barretenberg release loaded from its own shared library.
//!
//! Each library is self-contained (own static libc++, every symbol local) and exports only
//! `azbb_api` (msgpack command in, msgpack response out) and `azbb_free`. Loaded with
//! `RTLD_LOCAL`, so two releases never resolve each other's symbols.

use std::path::{Path, PathBuf};

use crate::{ProverError, Result};

type ApiFn = unsafe extern "C" fn(*const u8, usize, *mut *mut u8, *mut usize);
type FreeFn = unsafe extern "C" fn(*mut core::ffi::c_void);

/// File name of a release's library inside the app's native library directory.
/// Android/Linux: `libbb_v6.0.0-rc.6.so`. Apple: a framework, whose name may not hold `.`/`-`.
pub fn file_name(release: &str) -> String {
    if cfg!(target_vendor = "apple") {
        let name = format!("AzBB_{}", release.replace(['.', '-'], "_"));
        format!("{name}.framework/{name}")
    } else {
        format!("libbb_v{release}.so")
    }
}

pub fn path_in(dir: &Path, release: &str) -> PathBuf {
    dir.join(file_name(release))
}

pub struct BbLibrary {
    api: ApiFn,
    free: FreeFn,
    // Declared last: the function pointers above must not outlive the mapping.
    _lib: libloading::Library,
}

impl BbLibrary {
    pub fn load(path: &Path) -> Result<Self> {
        let err = |message: String| ProverError::Library { path: path.display().to_string(), message };
        if !path.exists() {
            return Err(err("not found (build it with scripts/build-bb-shared.sh)".into()));
        }
        // SAFETY: loading a library we built; its initialisers only set up Barretenberg's statics.
        let lib = unsafe { libloading::Library::new(path) }.map_err(|e| err(e.to_string()))?;
        // SAFETY: the shim in scripts/build-bb-shared.sh exports exactly these C signatures.
        let (api, free) = unsafe {
            let api = *lib.get::<ApiFn>(b"azbb_api\0").map_err(|e| err(e.to_string()))?;
            let free = *lib.get::<FreeFn>(b"azbb_free\0").map_err(|e| err(e.to_string()))?;
            (api, free)
        };
        Ok(Self { api, free, _lib: lib })
    }

    /// Sends one msgpack-encoded bbapi command and returns the msgpack response.
    pub fn call(&mut self, input: &[u8]) -> std::result::Result<Vec<u8>, String> {
        let mut out: *mut u8 = std::ptr::null_mut();
        let mut len = 0usize;
        // SAFETY: valid input slice and out-pointers; bbapi turns C++ exceptions into an ErrorResponse.
        unsafe { (self.api)(input.as_ptr(), input.len(), &mut out, &mut len) };
        if out.is_null() || len == 0 {
            return Err("bbapi returned no response".into());
        }
        // SAFETY: `len` initialised bytes at `out`, released with the same library's allocator.
        let response = unsafe { std::slice::from_raw_parts(out, len).to_vec() };
        unsafe { (self.free)(out.cast()) };
        Ok(response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names_per_platform() {
        let name = file_name("6.0.0-rc.6");
        if cfg!(target_vendor = "apple") {
            assert_eq!(name, "AzBB_6_0_0_rc_6.framework/AzBB_6_0_0_rc_6");
        } else {
            assert_eq!(name, "libbb_v6.0.0-rc.6.so");
        }
    }

    #[test]
    fn a_missing_library_is_a_clear_error() {
        let err = BbLibrary::load(&path_in(Path::new("/nowhere"), "5.1.0")).err().unwrap();
        assert!(err.to_string().contains("build-bb-shared.sh"), "{err}");
    }

    #[test]
    fn a_library_without_the_shim_is_rejected() {
        // Any shared library that lacks azbb_api: the C library itself.
        let libc = ["/lib/x86_64-linux-gnu/libc.so.6", "/usr/lib/libc.so.6", "/lib/aarch64-linux-gnu/libc.so.6"]
            .into_iter()
            .map(Path::new)
            .find(|p| p.exists());
        if let Some(libc) = libc {
            let err = BbLibrary::load(libc).err().unwrap();
            assert!(err.to_string().contains("azbb_api"), "{err}");
        }
    }
}
