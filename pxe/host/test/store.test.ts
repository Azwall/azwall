import { afterEach, describe, expect, it } from 'vitest';

import { clearBroken, isMarkedBroken, markBroken, retryDelay } from '../src/store';

/** The tests run without a browser: a minimal localStorage. */
const items = new Map<string, string>();
(globalThis as { localStorage?: unknown }).localStorage ??= {
  getItem: (k: string) => items.get(k) ?? null,
  setItem: (k: string, v: string) => void items.set(k, v),
  removeItem: (k: string) => void items.delete(k),
};

describe('store', () => {
  afterEach(() => clearBroken('pxe-x'));

  it('retries a failed flush sooner first, then backs off to a minute', () => {
    expect([0, 1, 2, 5, 6, 20].map(retryDelay)).toEqual([1_000, 2_000, 4_000, 32_000, 60_000, 60_000]);
  });

  it('remembers a store whose files refused a write, until it is discarded', () => {
    expect(isMarkedBroken('pxe-x')).toBe(false);
    markBroken('pxe-x');
    expect(isMarkedBroken('pxe-x')).toBe(true);
    clearBroken('pxe-x');
    expect(isMarkedBroken('pxe-x')).toBe(false);
  });
});
