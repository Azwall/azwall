// Everything an approval is about, for the full view the app opens from its panel. The panel shows the summary
// (gateway.ts, calls.ts); these sections hold the rest, in full: whole addresses and values, every contract and
// function a site asks for, every call, argument, fee setting and authorization a transaction carries, and the
// request itself. Values are never shortened here: the app wraps and lets them be copied.
import type { AbiType } from '@aztec/stdlib/abi';

import { type ArtifactOf, defaultFeePayer, type ReadCall, readCall } from './calls';
import { type Json, list, obj } from './json';

/** One labelled value. */
export type Row = { label: string; value: string };
/** A titled group of rows, shown as one block. */
export type Section = { title: string; rows: Row[] };
/** The name of the contract at an address, if the wallet knows it. */
export type NameOf = (address: string) => Promise<string | undefined>;

const yes = (b: unknown) => (b === true ? 'Yes' : 'No');
/** A raw request is shown up to this many characters: past it, it is only noise on a phone. */
export const RAW_LIMIT = 20_000;

/** A value in full: numbers in decimal, addresses and fields whole, structs and arrays spelled out. */
export function full(v: unknown): string {
  if (v === undefined || v === null) return '';
  if (typeof v === 'bigint' || typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (Array.isArray(v)) return `[${v.map(full).join(', ')}]`;
  if (typeof v === 'object' && v.constructor !== Object) return String(v);
  if (typeof v === 'object') return `{${Object.entries(v).map(([k, x]) => `${k}: ${full(x)}`).join(', ')}}`;
  return String(v);
}

/** A Noir type as a developer reads it: Field, u128, bool, [Field; 3], or a struct's name. */
export function typeLabel(t: AbiType): string {
  const a = t as unknown as Json;
  switch (a.kind) {
    case 'field':
      return 'Field';
    case 'boolean':
      return 'bool';
    case 'integer':
      return `${a.sign === 'signed' ? 'i' : 'u'}${String(a.width)}`;
    case 'string':
      return `str<${String(a.length)}>`;
    case 'array':
      return `[${typeLabel(a.type as AbiType)}; ${String(a.length)}]`;
    case 'tuple':
      return `(${list(a.fields).map(f => typeLabel(f as AbiType)).join(', ')})`;
    case 'struct':
      return String(a.path ?? 'struct').split('::').pop() ?? 'struct';
    default:
      return String(a.kind ?? '?');
  }
}

/** Rows for the given pairs, without the empty ones. */
function rowsOf(pairs: [string, unknown][]): Row[] {
  return pairs.filter(([, v]) => v !== undefined && v !== null && v !== '').map(([label, v]) => ({ label, value: full(v) }));
}

/** An object's leaves as rows, `Fee › gasSettings › gasLimits › daGas`, a few levels deep. */
function flatten(value: unknown, path: string, depth = 0): Row[] {
  if (value === undefined || value === null) return [];
  const o = obj(value);
  if (typeof value !== 'object' || Array.isArray(value) || depth > 4 || !Object.keys(o).length) return [{ label: path, value: full(value) }];
  return Object.entries(o).flatMap(([k, v]) => flatten(v, `${path} › ${k}`, depth + 1));
}

/** The request as sent, indented, up to RAW_LIMIT characters. */
export function rawSection(value: unknown, title = 'Raw request'): Section {
  const text = JSON.stringify(value, (_k, v) => (typeof v === 'bigint' ? v.toString() : v), 2) ?? '';
  const shown = text.length > RAW_LIMIT ? `${text.slice(0, RAW_LIMIT)}\n… (${text.length - RAW_LIMIT} more characters)` : text;
  return { title, rows: [{ label: 'JSON', value: shown }] };
}

/** A field holding a small number (chain id, rollup version) in decimal, as networks are known by. */
function decimal(v: unknown): string | undefined {
  if (v === undefined || v === null) return undefined;
  try {
    return BigInt(String(v)).toString();
  } catch {
    return full(v);
  }
}

/** A connection request: the site as the browser knows it, then as it names itself, and the network it wants. */
export function connectSections(origin: string, appId: unknown, appName: unknown, chainInfo: unknown): Section[] {
  const chain = obj(chainInfo);
  return [
    { title: 'Site', rows: rowsOf([['Address (from the browser)', origin], ['Calls itself', appName], ['App id', appId]]) },
    { title: 'Network it asks for', rows: rowsOf([['L1 chain id', decimal(chain.chainId)], ['Rollup version', decimal(chain.version)]]) },
  ];
}

/**
 * Rows for contract-function patterns: which function of which contract, `*` meaning any. `kind` prefixes the
 * labels when a section mixes transactions and utilities.
 */
async function patternRows(scope: unknown, kind: string, nameOf: NameOf): Promise<Row[]> {
  if (scope === '*') return [{ label: kind || 'Functions', value: 'Any function of any contract' }];
  const rows: Row[] = [];
  for (const p of list(scope).map(obj)) {
    const contract = String(p.contract ?? '');
    const fn = p.function === '*' ? 'any function' : String(p.function ?? '?');
    const name = contract === '*' ? 'Any contract' : ((await nameOf(contract).catch(() => undefined)) ?? 'Contract');
    rows.push({ label: `${kind ? `${kind} · ` : ''}${name}: ${fn}`, value: contract === '*' ? 'any address' : contract });
    if (p.additionalScopes !== undefined) {
      const also = p.additionalScopes === '*' ? 'any account' : list(p.additionalScopes).map(full).join('\n');
      rows.push({ label: '  … may also read for', value: also });
    }
  }
  return rows;
}

/** Rows for a list of contract addresses (or `*`), named when the wallet knows them. */
async function contractRows(contracts: unknown, nameOf: NameOf, noun: string): Promise<Row[]> {
  if (contracts === '*') return [{ label: `${noun}s`, value: `Any ${noun.toLowerCase()}` }];
  const rows: Row[] = [];
  for (const c of list(contracts)) {
    const name = await nameOf(full(c)).catch(() => undefined);
    rows.push({ label: name ?? noun, value: full(c) });
  }
  return rows;
}

/** One capability a site asks for, in full. */
async function capabilitySection(c: Json, account: string, nameOf: NameOf): Promise<Section> {
  switch (c.type) {
    case 'accounts':
      return { title: 'Your account', rows: rowsOf([['Account it will see', account], ['May read your accounts', yes(c.canGet)], ['May ask you for authorizations', yes(c.canCreateAuthWit)]]) };
    case 'contracts': {
      const flags = rowsOf([['May register them in your wallet', yes(c.canRegister)], ['May read their metadata', yes(c.canGetMetadata)]]);
      return { title: 'Contracts', rows: [...flags, ...(await contractRows(c.contracts, nameOf, 'Contract'))] };
    }
    case 'contractClasses': {
      const flags = rowsOf([['May register them in your wallet', yes(c.canRegister)], ['May read their metadata', yes(c.canGetMetadata)]]);
      return { title: 'Contract classes', rows: [...flags, ...(await contractRows(c.classes, async () => undefined, 'Class'))] };
    }
    case 'simulation': {
      const txs = await patternRows(obj(c.transactions).scope, 'Transaction', nameOf);
      const utilities = await patternRows(obj(c.utilities).scope, 'Utility', nameOf);
      return { title: 'Simulations (nothing is proven or sent)', rows: [...txs, ...utilities] };
    }
    case 'transaction':
      return { title: 'Transactions (each one still asks you)', rows: await patternRows(c.scope, '', nameOf) };
    case 'data': {
      const events = await contractRows(obj(c.privateEvents).contracts, nameOf, 'Private events of');
      return { title: 'Data', rows: [...rowsOf([['May read your address book', yes(c.addressBook)]]), ...events] };
    }
    default:
      return { title: `Other: ${String(c.type)}`, rows: [{ label: 'Request', value: JSON.stringify(c) }] };
  }
}

/** A capability request (requestCapabilities): what the site says it is, then each capability in full. */
export async function capabilitySections(manifest: unknown, account: string, nameOf: NameOf): Promise<Section[]> {
  const m = obj(manifest);
  const meta = obj(m.metadata);
  const site = rowsOf([['Name', meta.name], ['Version', meta.version], ['Description', meta.description], ['Website', meta.url], ['Protocol version', m.version]]);
  const caps = await Promise.all(list(m.capabilities).map(c => capabilitySection(obj(c), account, nameOf)));
  return [{ title: 'What the site says about itself', rows: site }, ...caps];
}

/** One call: contract, function, kind, and each argument with its type and decoded value. */
export function callSection(call: unknown, read: ReadCall, title: string): Section {
  const c = obj(call);
  const kind = [c.type, c.isStatic === true ? 'static (read only)' : undefined].filter(Boolean).join(' · ');
  const head = rowsOf([
    ['Contract', read.contract ?? 'Unknown: the wallet does not know this contract'],
    ['Address', c.to],
    // Without the contract's artifact, the name is only the site's label for the call.
    ['Function', read.fn?.name ?? (c.name === undefined ? undefined : `${String(c.name)} (as the site names it, unverified)`)],
    ['Selector', c.selector],
    ['Kind', kind],
    ['Hides the sender', c.hideMsgSender === true ? 'Yes' : undefined],
  ]);
  const params = read.fn?.parameters ?? [];
  const args =
    read.values && params.length
      ? params.map(p => ({ label: `${p.name} · ${typeLabel(p.type)}`, value: full(read.values?.[p.name]) }))
      : list(c.args).map((a, i) => ({ label: `Argument ${i + 1} (raw field)`, value: full(a) }));
  return { title: `${title}: ${read.fee ? 'fee payment, ' : ''}${read.name}${read.fn ? '' : ' (unverified)'}`, rows: [...head, ...args] };
}

/** Who sends a transaction, how its fee is paid, and what is attached to it. */
function attachmentsSection(payload: Json, opts: Json, title: string): Section {
  const witnesses = list(payload.authWitnesses).map((w, i) => ({ label: `Authorization ${i + 1}`, value: full(obj(w).requestHash ?? w) }));
  const counts = rowsOf([
    ['Capsules', list(payload.capsules).length || undefined],
    ['Extra hashed arguments', list(payload.extraHashedArgs).length || undefined],
  ]);
  const payer = payload.feePayer ?? (opts.fee === undefined ? defaultFeePayer() : undefined);
  const rows = [...rowsOf([['Sent as', opts.from], ['Fee paid by', payer]]), ...flatten(opts.fee, 'Fee'), ...witnesses, ...counts];
  return { title, rows };
}

/** A transaction (sendTx): each call in order, then sender, fee and attachments. `prefix` numbers it in a batch. */
export async function txSections(args: unknown[], artifactOf: ArtifactOf, prefix = ''): Promise<Section[]> {
  const payload = obj(args[0]);
  const calls = list(payload.calls);
  const read = await Promise.all(calls.map(c => readCall(c, artifactOf)));
  const sections = calls.map((c, i) => callSection(c, read[i], `${prefix}Call ${i + 1}`));
  return [...sections, attachmentsSection(payload, obj(args[1]), `${prefix}Sender, fee and attachments`)];
}

/** An authorization (createAuthWit): whom it lets act as you, and for which call, or the raw hash. */
export async function authwitSections(args: unknown[], artifactOf: ArtifactOf, prefix = ''): Promise<Section[]> {
  const intent = args[1];
  const i = obj(intent);
  const who = { title: `${prefix}Authorization`, rows: rowsOf([['Signed as', args[0]], ['Who may use it', i.caller ?? i.consumer]]) };
  if (i.call !== undefined) return [who, callSection(i.call, await readCall(i.call, artifactOf), `${prefix}Call it covers`)];
  const hash = rowsOf([['Inner hash', i.innerHash], ['Message hash', i.innerHash === undefined ? intent : undefined]]);
  return [{ ...who, rows: [...who.rows, ...hash] }];
}
