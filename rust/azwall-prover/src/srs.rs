//! CRS (structured reference string) files bundled with the apps. The files are universal (BN254
//! and Grumpkin), so every Barretenberg release uses the same ones (`scripts/fetch-crs.sh`).

use std::path::Path;

use crate::{ProverError, Result};

/// bb.js `DEFAULT_BB_CRS_SIZE`.
pub const G1_POINTS: u32 = 1 << 19;
/// bb.js `initSRSChonk` Grumpkin size.
pub const GRUMPKIN_POINTS: u32 = 1 << 16;

pub const G1_FILE: &str = "g1_compressed.dat";
pub const G2_FILE: &str = "g2.dat";
pub const GRUMPKIN_FILE: &str = "grumpkin_g1_v2.dat";

pub struct Srs {
    /// Compressed BN254 G1 points (32 bytes each); Barretenberg decompresses them.
    pub g1: Vec<u8>,
    pub g1_points: u32,
    pub g2: Vec<u8>,
    pub grumpkin: Vec<u8>,
    pub grumpkin_points: u32,
}

impl Srs {
    pub fn load(dir: &Path) -> Result<Self> {
        Ok(Self {
            g1: read(dir, G1_FILE, G1_POINTS as usize * 32)?,
            g1_points: G1_POINTS,
            g2: read(dir, G2_FILE, 128)?,
            grumpkin: read(dir, GRUMPKIN_FILE, GRUMPKIN_POINTS as usize * 64)?,
            grumpkin_points: GRUMPKIN_POINTS,
        })
    }
}

fn read(dir: &Path, name: &str, min_len: usize) -> Result<Vec<u8>> {
    let path = dir.join(name);
    let bytes = std::fs::read(&path)
        .map_err(|e| ProverError::Srs(format!("{}: {e} (run scripts/fetch-crs.sh)", path.display())))?;
    if bytes.len() < min_len {
        return Err(ProverError::Srs(format!("{} is truncated: {} < {min_len} bytes", path.display(), bytes.len())));
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_files_point_to_the_fetch_script() {
        let err = Srs::load(Path::new("/no/crs/here")).err().unwrap();
        assert!(err.to_string().contains("fetch-crs.sh"));
    }

    #[test]
    fn truncated_files_are_rejected() {
        let dir = std::env::temp_dir().join(format!("azwall-srs-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        for name in [G1_FILE, G2_FILE, GRUMPKIN_FILE] {
            std::fs::write(dir.join(name), [0u8; 16]).unwrap();
        }
        let err = Srs::load(&dir).err().unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
        assert!(err.to_string().contains("truncated"));
    }
}
