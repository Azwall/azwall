import { describe, expect, it } from 'vitest';

import { feeModeOf } from '../src/fees';
import { addr } from '../src/token';

describe('fee mode', () => {
  it('is sponsored only where the Sponsored FPC is deployed', async () => {
    const sponsor = addr(`0x${'07'.repeat(32)}`);
    const deployed = { getContract: async (a: typeof sponsor) => (a.equals(sponsor) ? ({} as never) : undefined) };
    const absent = { getContract: async () => undefined };
    expect(await feeModeOf(deployed, sponsor)).toBe('sponsored');
    expect(await feeModeOf(absent, sponsor)).toBe('feeJuice');
  });
});
