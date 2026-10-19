// Human-readable lines for what a dApp asks, shown in the wallet's approval sheet. The calls arrive as JSON
// from the dApp, so every field is read defensively.

/** Methods that change state or sign, and therefore need an approval per call. */
export const NEEDS_APPROVAL = new Set(['sendTx', 'createAuthWit']);

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
    const calls = list(first.calls).map(describeCall);
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
