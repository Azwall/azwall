// What a connected site may do, as the capabilities the user accepted say (the manifest of requestCapabilities).
// Transactions and authorizations still ask the user each time; these checks keep a site within what the user
// accepted: it cannot read private state (simulations, utilities, private events) of other contracts, nor propose
// transactions outside its scope. A site asking again for what it already has gets it without a prompt; asking for
// more prompts with only what it adds.
import { type ArtifactOf, readCall, short } from './calls';
import { list, obj } from './json';
import { NotGranted } from './policy';

type Kind = 'transaction' | 'simulation' | 'utility' | 'events';
const KINDS: Kind[] = ['transaction', 'simulation', 'utility', 'events'];
type Flag = 'getAccounts' | 'createAuthWit';

/** Each kind's allowed `contract|function` keys, where `*` stands for any contract or any function. */
export type Grants = { flags: Set<Flag>; scopes: Record<Kind, Set<string>> };

const ANY = '*';

/** One address however the site wrote it (padded or not, any case), so keys compare. */
function address(v: unknown): string {
  const s = String(v ?? '');
  if (s === ANY) return ANY;
  try {
    return `0x${BigInt(s).toString(16)}`;
  } catch { // Not a number: compared as written, so it only matches itself.
    return s.toLowerCase();
  }
}

const key = (contract: unknown, fn: unknown) => `${address(contract)}|${fn === undefined ? ANY : String(fn)}`;

function patterns(scope: unknown): string[] {
  if (scope === ANY) return [key(ANY, ANY)];
  return list(scope).map(p => key(obj(p).contract, obj(p).function));
}

export function noGrants(): Grants {
  return { flags: new Set(), scopes: { transaction: new Set(), simulation: new Set(), utility: new Set(), events: new Set() } };
}

/** The grants a list of capabilities (a manifest's `capabilities`) asks for. */
export function grantsOf(capabilities: unknown): Grants {
  const g = noGrants();
  const add = (kind: Kind, keys: string[]) => keys.forEach(k => g.scopes[kind].add(k));
  for (const c of list(capabilities).map(obj)) {
    if (c.type === 'accounts' && c.canGet) g.flags.add('getAccounts');
    if (c.type === 'accounts' && c.canCreateAuthWit) g.flags.add('createAuthWit');
    if (c.type === 'transaction') add('transaction', patterns(c.scope));
    if (c.type === 'simulation') add('simulation', patterns(obj(c.transactions).scope));
    if (c.type === 'simulation') add('utility', patterns(obj(c.utilities).scope));
    const events = obj(obj(c).privateEvents).contracts;
    if (c.type === 'data') add('events', events === ANY ? [key(ANY, ANY)] : list(events).map(a => key(a, ANY)));
  }
  return g;
}

/** Whether `set` lets the site use function `fn` (undefined: a function the wallet could not verify) of `contract`. */
function allows(set: Set<string>, contract: unknown, fn: string | undefined): boolean {
  const named = fn === undefined ? [] : [key(contract, fn), key(ANY, fn)];
  return [...named, key(contract, ANY), key(ANY, ANY)].some(k => set.has(k));
}

/** Whether `set` already holds pattern `contract|fn`: any function of a contract needs a whole-contract grant. */
function covers(set: Set<string>, contract: string, fn: string): boolean {
  return fn === ANY ? set.has(key(contract, ANY)) || set.has(key(ANY, ANY)) : allows(set, contract, fn);
}

/** Everything in `a` or `b`. */
export function merge(a: Grants, b: Grants): Grants {
  const g = noGrants();
  for (const f of [...a.flags, ...b.flags]) g.flags.add(f);
  for (const kind of KINDS) for (const k of [...a.scopes[kind], ...b.scopes[kind]]) g.scopes[kind].add(k);
  return g;
}

/** What `asked` adds to `granted`, one entry per flag or pattern; empty when `granted` covers it all. */
export function added(granted: Grants, asked: Grants): { flags: Flag[]; scopes: { kind: Kind; contract: string; fn: string }[] } {
  const flags = [...asked.flags].filter(f => !granted.flags.has(f));
  const scopes = KINDS.flatMap(kind =>
    [...asked.scopes[kind]]
      .map(k => k.split('|'))
      .filter(([contract, fn]) => !covers(granted.scopes[kind], contract, fn))
      .map(([contract, fn]) => ({ kind, contract, fn })),
  );
  return { flags, scopes };
}

const FLAG_LINES: Record<Flag, string> = { getAccounts: 'See your account address', createAuthWit: 'Ask you for authorizations' };
const KIND_LINES: Record<Kind, string> = {
  transaction: 'Propose transactions with',
  simulation: 'Simulate',
  utility: 'Read private data with',
  events: 'Read private events of',
};

/** What a request adds, as the approval panel lists it. */
export async function addedLines(more: ReturnType<typeof added>, nameOf: (address: string) => Promise<string | undefined>): Promise<string[]> {
  // The name only labels the line; without it the short address says which contract.
  const contract = async (c: string) => (c === ANY ? 'any contract' : ((await nameOf(c).catch(() => undefined)) ?? short(c)));
  const scopes = await Promise.all(
    more.scopes.map(async s => {
      const target = await contract(s.contract);
      if (s.kind === 'events') return `${KIND_LINES.events} ${target}`;
      return `${KIND_LINES[s.kind]} ${s.fn === ANY ? 'any function' : s.fn} of ${target}`;
    }),
  );
  return [...more.flags.map(f => FLAG_LINES[f]), ...scopes];
}

async function permitCalls(set: Set<string>, calls: unknown[], artifactOf: ArtifactOf, what: string): Promise<void> {
  for (const call of calls) {
    const read = await readCall(call, artifactOf);
    // The network's sponsor paying the fee is the wallet's business, not a capability.
    if (read.fee) continue;
    const to = obj(call).to;
    // Without the contract's artifact the function name is the site's word: only a whole-contract grant covers it.
    if (!allows(set, to, read.fn?.name)) {
      throw new NotGranted(`this site was not allowed to ${what} ${read.name} on ${read.contract ?? short(to)}`);
    }
  }
}

/** Throws NotGranted unless the grants let the site call wallet method `method` with `args`. */
export async function permit(g: Grants, method: string, args: unknown[], artifactOf: ArtifactOf): Promise<void> {
  switch (method) {
    case 'getAccounts':
    case 'createAuthWit':
      if (!g.flags.has(method)) throw new NotGranted(`this site was not allowed to call ${method}`);
      return;
    case 'sendTx':
      return permitCalls(g.scopes.transaction, list(obj(args[0]).calls), artifactOf, 'send');
    case 'simulateTx':
    case 'profileTx':
      return permitCalls(g.scopes.simulation, list(obj(args[0]).calls), artifactOf, 'simulate');
    case 'executeUtility':
      return permitCalls(g.scopes.utility, [args[0]], artifactOf, 'read with');
    case 'getPrivateEvents': {
      const contract = obj(args[1]).contractAddress;
      if (!allows(g.scopes.events, contract, undefined)) throw new NotGranted(`this site was not allowed to read the private events of ${short(contract)}`);
      return;
    }
    default:
      // Chain reads, contract registration and the empty address book (policy.ts confines the rest).
      return;
  }
}

/**
 * Runs the tasks given for one key one after the other, whether the previous one succeeded or not. Capability
 * requests go through it per session: a site sending a second request while the first awaits the user (Palmyr does
 * on connection) has it judged against what the first granted, so it is not shown again.
 */
export function oneAtATime(): <T>(key: string, task: () => Promise<T>) => Promise<T> {
  const last = new Map<string, Promise<unknown>>();
  return <T>(key: string, task: () => Promise<T>) => {
    // A previous task's failure is its own caller's: here it only orders, so it must not stop the next one.
    const next = (last.get(key) ?? Promise.resolve()).catch(() => undefined).then(task);
    last.set(key, next);
    // Forgetting the finished tail only; its failure still reaches the caller through the returned promise.
    void next.catch(() => undefined).then(() => last.get(key) === next && last.delete(key));
    return next;
  };
}
