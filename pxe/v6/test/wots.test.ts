import { Fr } from '@aztec-labs/aztec.js/fields';
import { describe, expect, it } from 'vitest';

import { buildTree, CHAINS, digits, pathOf } from '../src/pq/wots';

describe('wots', () => {
  it('digits carry the checksum of the message digits', () => {
    const d = digits(Fr.ZERO);
    expect(d).toHaveLength(CHAINS);
    // All-zero digits: checksum 127 * 3 = 381 = 0b01_01_11_11_01 in base 4, little-endian.
    expect(d.slice(127)).toEqual([1, 3, 3, 1, 1]);
  });

  it('builds the same tree as contracts/pq_account (wots.nr test vector)', async () => {
    const levels = await buildTree(new Fr(0x5eedn), 2);
    expect(levels[2][0].toString()).toBe('0x08bcdda3f1f697a48dbe524ac3acbe0f66c8bb78f40a6d505ea1dc1a7506ef11');
    expect(pathOf(levels, 2)).toEqual([levels[0][3], levels[1][0]]);
  });
});
