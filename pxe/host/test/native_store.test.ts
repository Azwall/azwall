import { describe, expect, it } from 'vitest';

import type { Bridge } from '@azwall/pxe-shared';

import { AppCopies, seal, unseal } from '../src/native_store';

const key = new Uint8Array(32).fill(7);

/** The app's side of storeLoad / storeSave: files by name. */
function fakeApp() {
  const files = new Map<string, string>();
  const bridge = {
    callNative: async (method: string, p: { name: string; data?: string }) => {
      if (method === 'storeSave') files.set(p.name, p.data!);
      return method === 'storeLoad' ? { data: files.get(p.name) ?? null } : null;
    },
  } as unknown as Bridge;
  return { files, bridge };
}

describe('the app keeps sealed copies of the stores', () => {
  it('seals with the stores key, and nothing else opens them', async () => {
    const plain = new TextEncoder().encode('SQLite format 3');
    const sealed = await seal(key, plain);
    expect(sealed.byteLength).toBe(12 + plain.byteLength + 16);
    expect(await unseal(key, sealed)).toEqual(plain);
    await expect(unseal(new Uint8Array(32).fill(8), sealed)).rejects.toThrow();
  });

  it('gives back what was saved, and nothing for a copy another key sealed', async () => {
    const { files, bridge } = fakeApp();
    const bytes = new Uint8Array(100_000).fill(5);
    await new AppCopies(bridge, key).save('pxe-1-ab', bytes);
    expect(files.get('pxe-1-ab')!.length).toBeLessThan(2_000);
    expect(await new AppCopies(bridge, key).load('pxe-1-ab')).toEqual(bytes);
    const sealed = files.get('pxe-1-ab');
    expect(await new AppCopies(bridge, new Uint8Array(32).fill(9)).load('pxe-1-ab')).toBeNull();
    // Unreadable with this key: kept aside before anything replaces it.
    expect(files.get('pxe-1-ab-unreadable')).toBe(sealed);
    expect(await new AppCopies(bridge, key).load('wallet-1-ab')).toBeNull();
  });
});
