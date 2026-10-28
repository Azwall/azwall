// Contracts and contract classes a site registered in the PXE: their artifact, so their name and function names, is
// the site's word. The wallet still shows them, marked, so that a site cannot pass its own contract off as a known
// one ("Token"). Kept in the wallet's encrypted store: the marks outlive a restart, as the registrations do.
import { log } from '../log';

/** Where the marks are kept: a map of the wallet's store (address, or `class:` + class id → true). */
export type MarkStore = { keysAsync(): AsyncIterableIterator<string>; set(key: string, value: boolean): Promise<void> };

const bySites = new Set<string>();
let kept: MarkStore | null = null;

/** Reads the marks an earlier session kept, and keeps new ones there. */
export async function keepMarksIn(store: MarkStore): Promise<void> {
  kept = store;
  for await (const key of store.keysAsync()) bySites.add(key);
}

function mark(key: string): void {
  if (bySites.has(key)) return;
  bySites.add(key);
  // Marked for this session either way; the log says why it would be forgotten at the next.
  kept?.set(key, true).catch(err => log('dapp', `keeping a site's registration mark: ${err instanceof Error ? err.message : String(err)}`, 'warn'));
}

/** `address` was registered by a site and was unknown to the wallet before. */
export function markSiteRegistered(address: string): void {
  mark(address.toLowerCase());
}

export function isSiteRegistered(address: string): boolean {
  return bySites.has(address.toLowerCase());
}

/** A contract class (its artifact) a site registered, unknown to the wallet before. */
export function markSiteClass(classId: string): void {
  mark(`class:${classId.toLowerCase()}`);
}

export function isSiteClass(classId: string): boolean {
  return bySites.has(`class:${classId.toLowerCase()}`);
}
