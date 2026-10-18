// Binary framing of native prove requests and responses; the Rust side is rust/azwall-prover/src/wire.rs.
// All integers are little-endian u32.
//   request  "AZS2" count, then per step: kind, name, bytecode (gzipped), witness (gzipped), vk
//   response "AZP1" field count, fields (32 bytes each), compressed proof, meta JSON

export type ProveStep = { kind: number; name: string; bytecode: Uint8Array; witness: Uint8Array; vk: Uint8Array };

export type ProveResponse = { fields: Uint8Array[]; compressed: Uint8Array; meta: Record<string, unknown> };

const REQUEST_MAGIC = [0x41, 0x5a, 0x53, 0x32];
const RESPONSE_MAGIC = [0x41, 0x5a, 0x50, 0x31];

export function encodeRequest(steps: ProveStep[]): Uint8Array {
  const enc = new TextEncoder();
  const names = steps.map(s => enc.encode(s.name));
  let size = 8;
  steps.forEach((s, i) => (size += 4 + 4 * 4 + names[i].length + s.bytecode.length + s.witness.length + s.vk.length));
  const out = new Uint8Array(size);
  const view = new DataView(out.buffer);
  out.set(REQUEST_MAGIC, 0);
  let o = 4;
  const u32 = (v: number) => {
    view.setUint32(o, v, true);
    o += 4;
  };
  const bytes = (b: Uint8Array) => {
    u32(b.length);
    out.set(b, o);
    o += b.length;
  };
  u32(steps.length);
  steps.forEach((s, i) => {
    u32(s.kind);
    bytes(names[i]);
    bytes(s.bytecode);
    bytes(s.witness);
    bytes(s.vk);
  });
  return out;
}

export function decodeResponse(buf: Uint8Array): ProveResponse {
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  let o = 0;
  const take = (n: number) => {
    if (o + n > buf.length) throw new Error(`prover response truncated at offset ${o}`);
    const s = buf.slice(o, o + n);
    o += n;
    return s;
  };
  const u32 = () => {
    take(4);
    return view.getUint32(o - 4, true);
  };
  const magic = take(4);
  if (!RESPONSE_MAGIC.every((b, i) => magic[i] === b)) throw new Error('bad prover response magic');
  const count = u32();
  const fields = Array.from({ length: count }, () => take(32));
  const compressed = take(u32());
  const meta = JSON.parse(new TextDecoder().decode(take(u32()))) as Record<string, unknown>;
  if (o !== buf.length) throw new Error(`${buf.length - o} trailing bytes in prover response`);
  return { fields, compressed, meta };
}

/** Base64 without size limits (String.fromCharCode on a whole buffer overflows the call stack). */
export function toBase64(bytes: Uint8Array): string {
  let s = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) s += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  return btoa(s);
}

export function fromBase64(b64: string): Uint8Array {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}
