//! Compact binary framing between the PXE (JavaScript) and the native prover. JSON would inflate
//! the multi-megabyte bytecodes and witnesses; this keeps the payload close to the raw size.
//! Same format as the prototype, so captured requests stay usable as fixtures.
//!
//! All integers are little-endian u32.
//!
//! Request (`AZS2`): step count, then per execution step: kind, name, bytecode, witness, vk, each
//! byte field as (len, bytes). Bytecode and witness are gzipped, as the PXE holds them.
//!
//! Response (`AZP1`): field count, fields (32 bytes each), compressed proof (len, bytes), then a
//! JSON object with timings (len, bytes).

use std::io::{Read, Write};

use crate::{ProverError, Result};

pub const REQUEST_MAGIC: &[u8; 4] = b"AZS2";
pub const RESPONSE_MAGIC: &[u8; 4] = b"AZP1";
const MAX_STEPS: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecutionStep {
    /// Circuit kind, as the PXE reports it (v6 bindings pass it to Barretenberg).
    pub kind: u32,
    pub name: String,
    /// Inflated ACIR bytecode.
    pub bytecode: Vec<u8>,
    /// Inflated witness.
    pub witness: Vec<u8>,
    pub vk: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProofResponse {
    pub fields: Vec<[u8; 32]>,
    pub compressed: Vec<u8>,
    pub meta_json: String,
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.buf.len()).ok_or_else(|| {
            ProverError::Wire(format!("truncated: need {n} bytes at offset {}, have {}", self.pos, self.buf.len()))
        })?;
        let slice = &self.buf[self.pos..end];
        self.pos = end;
        Ok(slice)
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().expect("4 bytes")))
    }

    fn bytes(&mut self) -> Result<&'a [u8]> {
        let n = self.u32()? as usize;
        self.take(n)
    }

    fn finish(&self) -> Result<()> {
        match self.buf.len() - self.pos {
            0 => Ok(()),
            n => Err(ProverError::Wire(format!("{n} trailing bytes"))),
        }
    }
}

pub fn decode_request(buf: &[u8]) -> Result<Vec<ExecutionStep>> {
    decode_request_capped(buf, MAX_REQUEST)
}

fn decode_request_capped(buf: &[u8], max_request: u64) -> Result<Vec<ExecutionStep>> {
    let mut r = Reader { buf, pos: 0 };
    if r.take(4)? != REQUEST_MAGIC {
        return Err(ProverError::Wire("bad request magic".into()));
    }
    let count = r.u32()? as usize;
    if count == 0 || count > MAX_STEPS {
        return Err(ProverError::Wire(format!("implausible execution step count {count}")));
    }
    let mut steps = Vec::with_capacity(count);
    let mut unpacked: u64 = 0;
    for _ in 0..count {
        let kind = r.u32()?;
        let name = String::from_utf8(r.bytes()?.to_vec()).map_err(|e| ProverError::Wire(e.to_string()))?;
        let bytecode = gunzip(r.bytes()?, &name, "bytecode")?;
        let witness = gunzip(r.bytes()?, &name, "witness")?;
        let vk = r.bytes()?.to_vec();
        // Each field is capped, but 64 steps of two capped fields would still be gigabytes: so is their sum.
        unpacked += (bytecode.len() + witness.len()) as u64;
        if unpacked > max_request {
            return Err(ProverError::Wire(format!("the request unpacks to more than {} MiB", max_request >> 20)));
        }
        steps.push(ExecutionStep { kind, name, bytecode, witness, vk });
    }
    r.finish()?;
    Ok(steps)
}

/// The inverse of [`decode_request`]; the PXE side does this in JavaScript, tests do it here.
pub fn encode_request(steps: &[ExecutionStep]) -> Result<Vec<u8>> {
    let mut out = REQUEST_MAGIC.to_vec();
    put_u32(&mut out, steps.len())?;
    for step in steps {
        put_u32(&mut out, step.kind as usize)?;
        put_bytes(&mut out, step.name.as_bytes())?;
        put_bytes(&mut out, &gzip(&step.bytecode)?)?;
        put_bytes(&mut out, &gzip(&step.witness)?)?;
        put_bytes(&mut out, &step.vk)?;
    }
    Ok(out)
}

pub fn encode_response(fields: &[Vec<u8>], compressed: &[u8], meta_json: &str) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(16 + fields.len() * 32 + compressed.len() + meta_json.len());
    out.extend_from_slice(RESPONSE_MAGIC);
    put_u32(&mut out, fields.len())?;
    for field in fields {
        if field.len() != 32 {
            return Err(ProverError::Wire(format!("proof field of {} bytes, expected 32", field.len())));
        }
        out.extend_from_slice(field);
    }
    put_bytes(&mut out, compressed)?;
    put_bytes(&mut out, meta_json.as_bytes())?;
    Ok(out)
}

pub fn decode_response(buf: &[u8]) -> Result<ProofResponse> {
    let mut r = Reader { buf, pos: 0 };
    if r.take(4)? != RESPONSE_MAGIC {
        return Err(ProverError::Wire("bad response magic".into()));
    }
    let count = r.u32()? as usize;
    let fields = (0..count).map(|_| Ok(r.take(32)?.try_into().expect("32 bytes"))).collect::<Result<Vec<_>>>()?;
    let compressed = r.bytes()?.to_vec();
    let meta_json = String::from_utf8(r.bytes()?.to_vec()).map_err(|e| ProverError::Wire(e.to_string()))?;
    r.finish()?;
    Ok(ProofResponse { fields, compressed, meta_json })
}

fn put_u32(out: &mut Vec<u8>, n: usize) -> Result<()> {
    let n = u32::try_from(n).map_err(|_| ProverError::Wire(format!("{n} does not fit in a u32")))?;
    out.extend_from_slice(&n.to_le_bytes());
    Ok(())
}

fn put_bytes(out: &mut Vec<u8>, bytes: &[u8]) -> Result<()> {
    put_u32(out, bytes.len())?;
    out.extend_from_slice(bytes);
    Ok(())
}

/// The largest bytecode or witness a request may unpack to: far above any Aztec circuit, far below what would
/// exhaust a phone's memory if a malformed request expanded without end.
const MAX_UNPACKED: u64 = 256 * 1024 * 1024;

/// The most a whole request may unpack to, all steps together.
const MAX_REQUEST: u64 = 1024 * 1024 * 1024;

fn gunzip(data: &[u8], circuit: &str, what: &str) -> Result<Vec<u8>> {
    gunzip_capped(data, MAX_UNPACKED, circuit, what)
}

fn gunzip_capped(data: &[u8], cap: u64, circuit: &str, what: &str) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(data.len() * 4);
    flate2::read::GzDecoder::new(data)
        .take(cap + 1)
        .read_to_end(&mut out)
        .map_err(|e| ProverError::Wire(format!("{circuit}: {what} is not valid gzip: {e}")))?;
    if out.len() as u64 > cap {
        return Err(ProverError::Wire(format!("{circuit}: {what} unpacks to more than {cap} bytes")));
    }
    Ok(out)
}

fn gzip(data: &[u8]) -> Result<Vec<u8>> {
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
    encoder.write_all(data).map_err(|e| ProverError::Wire(e.to_string()))?;
    encoder.finish().map_err(|e| ProverError::Wire(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_request_unpacking_to_too_much_altogether_is_refused() {
        let steps = vec![step(0), step(1), step(2)];
        let encoded = encode_request(&steps).unwrap();
        let total: u64 = steps.iter().map(|s| (s.bytecode.len() + s.witness.len()) as u64).sum();
        assert!(decode_request_capped(&encoded, total).is_ok());
        let err = decode_request_capped(&encoded, total - 1).unwrap_err();
        assert!(err.to_string().contains("unpacks to more than"), "{err}");
    }

    fn step(i: u32) -> ExecutionStep {
        ExecutionStep {
            kind: i,
            name: format!("circuit_{i}"),
            bytecode: vec![i as u8; 1000],
            witness: vec![7; 300],
            vk: vec![9; 64],
        }
    }

    #[test]
    fn unpacking_stops_at_the_cap() {
        let packed = gzip(&[0u8; 10_000]).unwrap();
        assert_eq!(gunzip_capped(&packed, 10_000, "c", "witness").unwrap().len(), 10_000);
        let err = gunzip_capped(&packed, 9_999, "c", "witness").unwrap_err().to_string();
        assert!(err.contains("more than 9999 bytes"), "{err}");
    }

    #[test]
    fn request_round_trip() {
        let steps = vec![step(0), step(1), step(2)];
        let decoded = decode_request(&encode_request(&steps).unwrap()).unwrap();
        assert_eq!(decoded, steps);
    }

    #[test]
    fn rejects_bad_magic_counts_and_truncation() {
        let good = encode_request(&[step(1)]).unwrap();
        let mut bad_magic = good.clone();
        bad_magic[0] = b'X';
        assert!(decode_request(&bad_magic).is_err());
        assert!(decode_request(&good[..good.len() - 1]).is_err());
        let mut trailing = good.clone();
        trailing.push(0);
        assert!(decode_request(&trailing).is_err());
        assert!(decode_request(&encode_request(&[]).unwrap()).is_err());
    }

    #[test]
    fn rejects_non_gzip_payloads() {
        let mut buf = REQUEST_MAGIC.to_vec();
        for n in [1u32, 0, 1] {
            buf.extend_from_slice(&n.to_le_bytes());
        }
        buf.push(b'x');
        buf.extend_from_slice(&3u32.to_le_bytes());
        buf.extend_from_slice(b"raw");
        assert!(matches!(decode_request(&buf), Err(ProverError::Wire(m)) if m.contains("gzip")));
    }

    #[test]
    fn response_round_trip() {
        let fields = vec![vec![1u8; 32], vec![2u8; 32]];
        let buf = encode_response(&fields, b"compressed", "{\"totalMs\":1}").unwrap();
        let response = decode_response(&buf).unwrap();
        assert_eq!(response.fields, [[1u8; 32], [2u8; 32]]);
        assert_eq!(response.compressed, b"compressed");
        assert_eq!(response.meta_json, "{\"totalMs\":1}");
    }

    #[test]
    fn response_fields_must_be_32_bytes() {
        assert!(encode_response(&[vec![0u8; 31]], b"", "{}").is_err());
    }
}
