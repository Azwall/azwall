import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

import { CachedStore } from '../src/store';

/** The tests run without a browser: a minimal localStorage. */
const items = new Map<string, string>();
(globalThis as { localStorage?: unknown }).localStorage ??= {
  getItem: (k: string) => items.get(k) ?? null,
  setItem: (k: string, v: string) => void items.set(k, v),
  removeItem: (k: string) => void items.delete(k),
};

// Node's fetch cannot read the SQLite WebAssembly from a file: URL, as the browser reads it from the page's origin.
const realFetch = globalThis.fetch;
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input instanceof Request ? input.url : input);
  if (!url.startsWith('file:')) return realFetch(input, init);
  return new Response(await readFile(decodeURIComponent(new URL(url).pathname)), { headers: { 'content-type': 'application/wasm' } });
}) as typeof fetch;

/** The kv-store's store object, as CachedStore sees it; the app keeps the data, so it is never written to. */
function storeObject() {
  const never = () => Promise.reject(new Error('the disk store was used'));
  return { allAsync: never, runAsync: never, transactionAsync: never, close: async () => {} } as Record<string, unknown>;
}

const INSERT = 'INSERT INTO data (slot, container, key, key_count, hash, value) VALUES (?, ?, ?, ?, ?, ?)';

describe('stores the app keeps a copy of', () => {
  it('hand the app the whole database, and start again from it', async () => {
    let copy: Uint8Array | null = null;
    const save = async (bytes: Uint8Array) => void (copy = bytes);
    const first = storeObject();
    const cache = await CachedStore.wrap(first as never, 'pxe-copy', { snapshot: null, save });
    await (first.transactionAsync as (cb: () => Promise<void>) => Promise<void>)(async () => {
      await (first.runAsync as (sql: string, bind: unknown[]) => Promise<unknown>)(INSERT, ['s1', 'c', new Uint8Array([1]), 0, 'h', new Uint8Array([42])]);
    });
    await cache.flush();
    expect(copy).not.toBeNull();
    expect(cache.stats.flushes).toBe(1);

    const again = storeObject();
    const reopened = await CachedStore.wrap(again as never, 'pxe-copy', { snapshot: copy, save });
    expect(reopened.rowCount()).toBe(1);
    const rows = await (again.allAsync as (sql: string) => Promise<unknown[][]>)('SELECT value FROM data WHERE slot = \'s1\'');
    expect(Array.from(rows[0]![0] as Uint8Array)).toEqual([42]);
  });

  it('stop saving once closed', async () => {
    let saves = 0;
    const store = storeObject();
    const cache = await CachedStore.wrap(store as never, 'pxe-closed', { snapshot: null, save: async () => void saves++ });
    await (store.close as () => Promise<void>)();
    await (store.transactionAsync as (cb: () => Promise<void>) => Promise<void>)(async () => {}).catch(() => {});
    await cache.flush();
    expect(saves).toBe(0);
  });
});

