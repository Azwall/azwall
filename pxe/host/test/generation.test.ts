import { describe, expect, it } from 'vitest';

import { generationName, nextGeneration } from '../src/generation';

/** The tests run without a browser: a minimal localStorage. */
const items = new Map<string, string>();
(globalThis as { localStorage?: unknown }).localStorage ??= {
  getItem: (k: string) => items.get(k) ?? null,
  setItem: (k: string, v: string) => void items.set(k, v),
  removeItem: (k: string) => void items.delete(k),
};

describe('store generations', () => {
  it('keep the first name, then move to a new one at each start-over', () => {
    expect(generationName('pxe-gen')).toBe('pxe-gen');
    expect(nextGeneration('pxe-gen')).toBe('pxe-gen');
    expect(generationName('pxe-gen')).toBe('pxe-gen-g1');
    expect(nextGeneration('pxe-gen')).toBe('pxe-gen-g1');
    expect(generationName('pxe-gen')).toBe('pxe-gen-g2');
    expect(generationName('wallet-gen')).toBe('wallet-gen');
  });
});
