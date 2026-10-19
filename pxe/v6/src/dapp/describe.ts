// Human-readable lines for what a dApp asks, shown in the wallet's approval sheet. The calls arrive as JSON
// from the dApp, so every field is read defensively.

/** Methods that change state or sign, and therefore need an approval per call. */
export const NEEDS_APPROVAL = new Set(['sendTx', 'createAuthWit']);

/** Fee-paying contract functions (Sponsored FPC, FPC entrypoints). */
const FEE_FUNCTIONS = new Set(['sponsor_unconditionally', 'fee_entrypoint_private', 'fee_entrypoint_public']);

type Json = Record<string, unknown>;

const obj = (v: unknown): Json => (v && typeof v === 'object' ? (v as Json) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const short = (v: unknown) => {
  const s = String(v ?? '?');
  return s.length > 14 ? `${s.slice(0, 8)}…${s.slice(-4)}` : s;
};

export function describeCall(call: unknown): string {
  const c = obj(call);
  const name = String(c.name ?? c.selector ?? 'unknown function');
  const kind = c.type ? ` (${String(c.type)})` : '';
  return `${name}${kind} on ${short(c.to)}`;
}

export function describeMessage(type: string, args: unknown[]): string[] {
  const first = obj(args[0]);
  if (type === 'sendTx') {
    // The fee payment comes first in the payload but says nothing of what the dApp does: list it last.
    const isFee = (c: unknown) => FEE_FUNCTIONS.has(String(obj(c).name));
    const all = list(first.calls);
    const calls = [...all.filter(c => !isFee(c)), ...all.filter(isFee)].map(describeCall);
    const authwits = list(first.authWitnesses).length;
    return authwits ? [...calls, `${authwits} authorization(s) attached`] : calls;
  }
  if (type === 'createAuthWit') return [`Authorize ${short(args[0])} to act for you`, JSON.stringify(args[1] ?? {}).slice(0, 200)];
  if (type === 'batch') return list(args[0]).map(b => String(obj(b).name ?? '?'));
  return [];
}

/** True if any call in a batch needs its own approval. */
export function batchNeedsApproval(args: unknown[]): boolean {
  return list(args[0]).some(b => NEEDS_APPROVAL.has(String(obj(b).name)));
}

const TX_HASH = /^0x[0-9a-f]{64}$/i;

/**
 * The hash of the transaction a dApp call sent: sendTx returns a TxHash or a receipt (with `wait`), and a batch
 * returns its results in a list. Only a few levels deep: results are dApp-shaped, not trusted.
 */
export function txHashOf(result: unknown, depth = 0): string | undefined {
  if (result === null || result === undefined || depth > 4) return undefined;
  if (Array.isArray(result)) return result.map(r => txHashOf(r, depth + 1)).find(h => h !== undefined);
  const text = String(result);
  if (TX_HASH.test(text)) return text;
  if (typeof result !== 'object') return undefined;
  const o = result as Json;
  return txHashOf(o.txHash, depth + 1) ?? txHashOf(o.result, depth + 1);
}
