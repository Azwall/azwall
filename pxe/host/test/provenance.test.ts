import { describe, expect, it } from 'vitest';

import { isSiteClass, isSiteRegistered, keepMarksIn, markSiteClass, markSiteRegistered } from '../src/dapp/provenance';

/** A store map in memory. */
function store(keys: string[] = []) {
  const map = new Map(keys.map(k => [k, true]));
  return {
    map,
    async *keysAsync() {
      yield* map.keys();
    },
    async set(key: string, value: boolean) {
      map.set(key, value);
    },
  };
}

describe('contracts a site registered', () => {
  it('are remembered across sessions, by address and by class', async () => {
    const first = store();
    await keepMarksIn(first);
    markSiteRegistered('0xABC');
    markSiteClass('0x0C1');
    await Promise.resolve();
    expect([...first.map.keys()]).toEqual(['0xabc', 'class:0x0c1']);
    // The next session starts from what was kept.
    await keepMarksIn(store([...first.map.keys(), '0xdef']));
    expect(isSiteRegistered('0xDEF')).toBe(true);
    expect(isSiteClass('0x0c1')).toBe(true);
    expect(isSiteRegistered('0x123')).toBe(false);
  });
});
