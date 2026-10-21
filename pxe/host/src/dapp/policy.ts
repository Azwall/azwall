// What a connected site may do, and as whom. A site is granted one account, the one selected when the user
// accepted its capabilities; the PXE holds every account of the wallet, so each call is checked so that it acts as,
// or reads for, that account only. Methods the wallet-sdk protocol does not need are refused.

type Json = Record<string, unknown>;

const obj = (v: unknown): Json => (v && typeof v === 'object' ? (v as Json) : {});
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const same = (a: unknown, b: string) => String(a ?? '').toLowerCase() === b.toLowerCase();

/** Methods that only read the chain or register public data in the PXE. */
const UNSCOPED = new Set(['getChainInfo', 'getContractMetadata', 'getContractClassMetadata', 'registerSender', 'registerContract', 'registerContractClass']);

/** Thrown when a site tries to act as, or read for, another account than the one it was granted. */
export class NotGranted extends Error {}

/**
 * The arguments with which `method` may run for a site granted `account`. `others` are the wallet's other accounts:
 * they are removed from the scopes a site asks for (a contract's own scope stays), and acting as one is refused.
 */
export function confine(method: string, args: unknown[], account: string, others: string[]): unknown[] {
  const isOther = (a: unknown) => others.some(o => same(a, o));
  const scopes = (v: unknown) => {
    const kept = list(v).filter(a => !isOther(a));
    return kept.length ? kept : [account];
  };
  const opts = obj(args[1]);
  if (UNSCOPED.has(method)) return args;
  switch (method) {
    case 'simulateTx':
    case 'profileTx':
    case 'sendTx':
      if (opts.from !== 'NO_FROM' && !same(opts.from, account)) throw new NotGranted('this site may only act as the account you connected');
      return [args[0], { ...opts, additionalScopes: Array.isArray(opts.additionalScopes) ? list(opts.additionalScopes).filter(a => !isOther(a)) : opts.additionalScopes }];
    case 'executeUtility':
    case 'getPrivateEvents':
      return [args[0], { ...opts, scopes: scopes(opts.scopes) }];
    case 'createAuthWit':
      if (!same(args[0], account)) throw new NotGranted('this site may only authorize actions for the account you connected');
      return args;
    default:
      throw new NotGranted(`${method} is not available to sites`);
  }
}

/**
 * Whether a message naming a session (or a pending discovery) `owner` may come from page `tabId` at `origin`: a
 * session answers only the page that opened it, so another page cannot take over a connection approved for a site.
 */
export function samePage(owner: { tabId: number; origin: string } | undefined, tabId: number, origin: string): boolean {
  return !owner || (owner.tabId === tabId && owner.origin === new URL(origin).origin);
}
