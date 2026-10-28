// Contracts a site registered in the PXE: their artifact, so their name and function names, is the site's word. The
// wallet still shows them, marked, so that a site cannot pass its own contract off as a known one ("Token").

const bySites = new Set<string>();

/** `address` was registered by a site and was unknown to the wallet before. */
export function markSiteRegistered(address: string): void {
  bySites.add(address.toLowerCase());
}

export function isSiteRegistered(address: string): boolean {
  return bySites.has(address.toLowerCase());
}
