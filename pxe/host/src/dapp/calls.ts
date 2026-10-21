// Reads what a dApp's calls really do, for the approval panel. A call names its function twice: `name`, a label the
// dApp chooses, and `selector`, what runs. The wallet looks the selector up in the contract's artifact (registered in
// the PXE), shows that function with its decoded arguments, and refuses a call whose label names another function.
import { Fr } from '@aztec/aztec.js/fields';
import { type AbiType, type ContractArtifact, type FunctionAbi, FunctionSelector } from '@aztec/stdlib/abi';

import { decodeStruct } from '../version';

/** The artifact of the contract at an address, if the PXE knows it. */
export type ArtifactOf = (address: string) => Promise<ContractArtifact | undefined>;

/** A call as the user should read it. */
export type ReadCall = { name: string; line: string; fee: boolean };

/** Fee-paying contract functions (Sponsored FPC, FPC entrypoints): listed after what the transaction does. */
export const FEE_FUNCTIONS = new Set(['sponsor_unconditionally', 'fee_entrypoint_private', 'fee_entrypoint_public']);

type Json = Record<string, unknown>;

const obj = (v: unknown): Json => (v && typeof v === 'object' ? (v as Json) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

export const short = (v: unknown) => {
  const s = String(v ?? '?');
  return s.length > 14 ? `${s.slice(0, 8)}…${s.slice(-4)}` : s;
};

/** A decoded argument, compact: numbers in full, addresses and fields shortened, at most 60 characters. */
export function show(v: unknown): string {
  const text = (() => {
    if (typeof v === 'bigint' || typeof v === 'number' || typeof v === 'boolean') return String(v);
    if (Array.isArray(v)) return `[${v.map(show).join(', ')}]`;
    if (v && typeof v === 'object' && v.constructor !== Object) return short(String(v));
    if (v && typeof v === 'object') return `{${Object.entries(v).map(([k, x]) => `${k}: ${show(x)}`).join(', ')}}`;
    return short(v);
  })();
  return text.length > 60 ? `${text.slice(0, 59)}…` : text;
}

async function bySelector(artifact: ContractArtifact, selector: string): Promise<FunctionAbi | undefined> {
  const wanted = FunctionSelector.fromString(selector);
  for (const fn of artifact.functions) {
    if ((await FunctionSelector.fromNameAndParameters(fn.name, fn.parameters)).equals(wanted)) return fn;
  }
  return undefined;
}

/** What one call does. Throws if its label is not the function its selector runs. */
export async function readCall(call: unknown, artifactOf: ArtifactOf): Promise<ReadCall> {
  const c = obj(call);
  const to = String(c.to ?? '');
  const label = String(c.name ?? 'unknown function');
  const artifact = await artifactOf(to).catch(() => undefined);
  const fn = artifact && c.selector !== undefined ? await bySelector(artifact, String(c.selector)).catch(() => undefined) : undefined;
  if (!artifact || !fn) return { name: label, line: `${label} on ${short(to)} (unverified: the wallet does not know this contract)`, fee: false };
  if (c.name !== undefined && label !== fn.name) throw new Error(`the call to ${short(to)} is labelled ${label} but runs ${fn.name}`);
  let args: string;
  try {
    // The parameters as one struct: decodes them in order, by name.
    const type = { kind: 'struct', path: fn.name, fields: fn.parameters.map(p => ({ name: p.name, type: p.type })) } as AbiType;
    const values = decodeStruct(type, list(c.args).map(a => Fr.fromString(String(a)))) as Record<string, unknown>;
    args = fn.parameters.map(p => `${p.name}: ${show(values[p.name])}`).join(', ');
  } catch {
    args = '…';
  }
  return { name: fn.name, line: `${fn.name}(${args}) on ${artifact.name} ${short(to)}`, fee: FEE_FUNCTIONS.has(fn.name) };
}

/** The lines of a transaction: what it does first, its fee payment last, then its attached authorizations. */
export async function describeTx(payload: unknown, artifactOf: ArtifactOf): Promise<string[]> {
  const p = obj(payload);
  const calls = await Promise.all(list(p.calls).map(c => readCall(c, artifactOf)));
  const lines = [...calls.filter(c => !c.fee), ...calls.filter(c => c.fee)].map(c => c.line);
  const authwits = list(p.authWitnesses).length;
  return authwits ? [...lines, `${authwits} authorization(s) attached`] : lines;
}

/** What an authorization lets another contract do: the call it covers, or an opaque hash the wallet cannot read. */
export async function describeAuthWit(intent: unknown, artifactOf: ArtifactOf): Promise<string[]> {
  const i = obj(intent);
  if (i.call === undefined) return [`An action the wallet cannot read (hash ${short(intent)}): approve only if you trust this site`];
  const call = await readCall(i.call, artifactOf);
  return [`Lets ${short(i.caller)} run, as you:`, call.line];
}
