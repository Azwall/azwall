// Reads what a dApp's calls really do, for the approval panel. A call names its function twice: `name`, a label the
// dApp chooses, and `selector`, what runs. The wallet looks the selector up in the contract's artifact (registered in
// the PXE), shows that function with its decoded arguments, and refuses a call whose label names another function.
import { Fr } from '@aztec/aztec.js/fields';
import { type AbiType, type ContractArtifact, type FunctionAbi, FunctionSelector, getAllFunctionAbis } from '@aztec/stdlib/abi';

import { decodeStruct } from '../version';
import { type Json, list, obj } from './json';

/** The artifact of the contract at an address, if the PXE knows it. */
export type ArtifactOf = (address: string) => Promise<ContractArtifact | undefined>;

/**
 * A call as the user should read it: `line` for the summary; the contract's name, the function and its decoded
 * arguments for the full details, when the wallet knows the contract.
 */
export type ReadCall = { name: string; line: string; fee: boolean; contract?: string; fn?: FunctionAbi; values?: Record<string, unknown> };

/** Fee-paying contract functions (Sponsored FPC, FPC entrypoints): listed after what the transaction does. */
export const FEE_FUNCTIONS = new Set(['sponsor_unconditionally', 'fee_entrypoint_private', 'fee_entrypoint_public']);

/**
 * The contracts whose fee functions really pay fees: the network's Sponsored FPC (open.ts). A function name proves
 * nothing, since a site can register its own contract with a function named like these; only these addresses count.
 */
const feePayers = new Set<string>();

export function trustFeePayers(addresses: string[]): void {
  feePayers.clear();
  for (const a of addresses) feePayers.add(a.toLowerCase());
}

/** Who pays a transaction that names no fee payer: the network's sponsor where there is one, else the account. */
export function defaultFeePayer(): string {
  return feePayers.size ? "The network's sponsor (no fee for you)" : 'Your account, with its Fee Juice';
}

export const short = (v: unknown) => {
  const s = String(v ?? '?');
  return s.length > 14 ? `${s.slice(0, 8)}…${s.slice(-4)}` : s;
};

/** A decoded argument, compact: small numbers in full, long ones, addresses and fields shortened, at most 60 characters. */
export function show(v: unknown): string {
  const text = (() => {
    if (typeof v === 'bigint' || typeof v === 'number') return short(String(v));
    if (typeof v === 'boolean') return String(v);
    if (Array.isArray(v)) return `[${v.map(show).join(', ')}]`;
    if (v && typeof v === 'object' && v.constructor !== Object) return short(String(v));
    if (v && typeof v === 'object') return `{${Object.entries(v).map(([k, x]) => `${k}: ${show(x)}`).join(', ')}}`;
    return short(v);
  })();
  return text.length > 60 ? `${text.slice(0, 59)}…` : text;
}

/** The function a selector runs, private, utility or public (public ones sit outside `functions`, behind its dispatch). */
/** A call's summary fits in about two lines of the approval panel; its details show every argument in full. */
export const LINE_LIMIT = 90;

/** `fn(args) on contract`, with as many leading arguments as fit in LINE_LIMIT, then `…`. */
function summary(fn: string, args: string[], contract: string): string {
  const line = (kept: string[]) => `${fn}(${[...kept, ...(kept.length < args.length ? ['…'] : [])].join(', ')}) on ${contract}`;
  let kept: string[] = [];
  for (const arg of args) {
    if (line([...kept, arg]).length > LINE_LIMIT) break;
    kept = [...kept, arg];
  }
  return line(kept);
}

async function bySelector(artifact: ContractArtifact, selector: string): Promise<FunctionAbi | undefined> {
  const wanted = FunctionSelector.fromString(selector);
  for (const fn of getAllFunctionAbis(artifact)) {
    if ((await FunctionSelector.fromNameAndParameters(fn.name, fn.parameters)).equals(wanted)) return fn;
  }
  return undefined;
}

/**
 * Each call is read once: an approval's summary, its full details and the activity entry of the transaction all read
 * the same call objects (an artifact lookup and a selector hash per function of the contract each time otherwise).
 */
const reads = new WeakMap<object, Promise<ReadCall>>();

/** What one call does. Throws if its label is not the function its selector runs. */
export function readCall(call: unknown, artifactOf: ArtifactOf): Promise<ReadCall> {
  if (!call || typeof call !== 'object') return readFresh(call, artifactOf);
  const known = reads.get(call);
  if (known) return known;
  const read = readFresh(call, artifactOf);
  reads.set(call, read);
  return read;
}

async function readFresh(call: unknown, artifactOf: ArtifactOf): Promise<ReadCall> {
  const c = obj(call);
  const to = String(c.to ?? '');
  const label = String(c.name ?? 'unknown function');
  const artifact = await artifactOf(to).catch(() => undefined);
  const fn = artifact && c.selector !== undefined ? await bySelector(artifact, String(c.selector)).catch(() => undefined) : undefined;
  if (!artifact || !fn) return { name: label, line: `${label} on ${short(to)} (unverified: the wallet does not know this contract)`, fee: false };
  if (c.name !== undefined && label !== fn.name) throw new Error(`the call to ${short(to)} is labelled ${label} but runs ${fn.name}`);
  let args = ['…'];
  let values: Record<string, unknown> | undefined;
  try {
    // The parameters as one struct: decodes them in order, by name.
    const type = { kind: 'struct', path: fn.name, fields: fn.parameters.map(p => ({ name: p.name, type: p.type })) } as AbiType;
    values = decodeStruct(type, list(c.args).map(a => Fr.fromString(String(a)))) as Record<string, unknown>;
    args = fn.parameters.map(p => `${p.name}: ${show(values?.[p.name])}`);
  } catch {
    values = undefined;
  }
  const line = summary(fn.name, args, `${artifact.name} ${short(to)}`);
  const fee = FEE_FUNCTIONS.has(fn.name) && feePayers.has(to.toLowerCase());
  return { name: fn.name, line, fee, contract: artifact.name, fn, values };
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
  if (i.call === undefined) {
    const who = i.consumer === undefined ? '' : `Lets ${short(i.consumer)} use `;
    return [`${who}an action the wallet cannot read (hash ${short(i.innerHash ?? intent)}): approve only if you trust this site`];
  }
  const call = await readCall(i.call, artifactOf);
  return [`Lets ${short(i.caller)} run, as you:`, call.line];
}
