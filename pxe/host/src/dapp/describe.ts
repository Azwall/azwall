// What the gateway reads back from dApp results. What a call does is read from the contract's artifact (calls.ts).

type Json = Record<string, unknown>;

/** Methods that change state or sign, and therefore need an approval per call. */
export const NEEDS_APPROVAL = new Set(['sendTx', 'createAuthWit']);

const TX_HASH = /^0x[0-9a-f]{64}$/i;

/**
 * The hash of the transaction a dApp call sent: sendTx returns `{ txHash }`, or `{ receipt }` when it waited (the SDK's
 * default), and a batch returns its results in a list. Only a few levels deep: results are dApp-shaped, not trusted.
 */
export function txHashOf(result: unknown, depth = 0): string | undefined {
  if (result === null || result === undefined || depth > 4) return undefined;
  if (Array.isArray(result)) return result.map(r => txHashOf(r, depth + 1)).find(h => h !== undefined);
  const text = String(result);
  if (TX_HASH.test(text)) return text;
  if (typeof result !== 'object') return undefined;
  const o = result as Json;
  return txHashOf(o.txHash, depth + 1) ?? txHashOf(o.receipt, depth + 1) ?? txHashOf(o.result, depth + 1);
}
