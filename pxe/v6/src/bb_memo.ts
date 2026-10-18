// Memoises the two pure bb.js calls that dominate the PXE's note sync outside the ACVM.
//
// Why: every new block, the PXE re-delivers the last 24 h of tagged logs to each contract's `sync_state`
// (see the prototype's docs/bench-results.md). For each log it re-derives the same ECDH shared secret (`grumpkinMul`) and
// re-hashes the same tags (`poseidon2Hash`), with the same inputs as on the previous block. On a Pixel 3 that
// is ≈ 1 s and ≈ 0.5 s of WASM per sync. Both functions are pure, so caching their output never changes a
// result. Batching them into one native call is not possible, because each call is awaited by an oracle in
// sequence.
//
// The cache lives in memory only: it holds derived secrets (shared secrets, scalar multiples), which are as
// sensitive as the keys the PXE already keeps in memory. It is bounded (LRU) and cleared with `clearBbMemo()`.
import { BarretenbergSync } from '@aztec-foundation/bb.js';

const MAX_ENTRIES = 20_000;

type Stats = { hits: number; misses: number };
export const bbMemoStats: Record<'grumpkinMul' | 'poseidon2Hash', Stats> = {
  grumpkinMul: { hits: 0, misses: 0 },
  poseidon2Hash: { hits: 0, misses: 0 },
};

const caches = { grumpkinMul: new Map<string, unknown>(), poseidon2Hash: new Map<string, unknown>() };

const hex = (b: Uint8Array) => {
  let s = '';
  for (let i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16);
  return s;
};

/** Deep copy of a response made of Uint8Arrays and plain objects, so callers never share cached buffers. */
function copy<T>(v: T): T {
  if (v instanceof Uint8Array) return new Uint8Array(v) as T;
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, copy(x)])) as T;
  return v;
}

function memo(name: keyof typeof caches, keyOf: (cmd: any) => string) {
  const proto = BarretenbergSync.prototype as any;
  const orig = proto[name];
  if (typeof orig !== 'function') throw new Error(`bb.js BarretenbergSync has no ${name}(); the memo layer is out of date`);
  if (orig.__azwallMemo) return;
  const cache = caches[name];
  const stats = bbMemoStats[name];
  const wrapped = function (this: unknown, cmd: unknown) {
    const key = keyOf(cmd);
    const hit = cache.get(key);
    if (hit !== undefined) {
      // Refresh recency (Map keeps insertion order).
      cache.delete(key);
      cache.set(key, hit);
      stats.hits++;
      return copy(hit);
    }
    stats.misses++;
    const res = orig.call(this, cmd);
    cache.set(key, copy(res));
    if (cache.size > MAX_ENTRIES) cache.delete(cache.keys().next().value!);
    return res;
  };
  wrapped.__azwallMemo = true;
  proto[name] = wrapped;
}

/** Installs the memo layer once, before the PXE starts. */
export function installBbMemo() {
  memo('grumpkinMul', (c: { point: { x: Uint8Array; y: Uint8Array }; scalar: Uint8Array }) =>
    `${hex(c.point.x)}${hex(c.point.y)}${hex(c.scalar)}`,
  );
  memo('poseidon2Hash', (c: { inputs: Uint8Array[] }) => c.inputs.map(hex).join(':'));
}

/** Drops every cached value (on lock or wipe). */
export function clearBbMemo() {
  for (const c of Object.values(caches)) c.clear();
}
