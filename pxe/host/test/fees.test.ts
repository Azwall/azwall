import { describe, expect, it } from 'vitest';

import { declaredFees, feeModeOf } from '../src/fees';
import { addr } from '../src/token';

describe('fee mode', () => {
  const sponsor = addr(`0x${'07'.repeat(32)}`);
  const deployed = { getContract: async (a: typeof sponsor) => (a.equals(sponsor) ? ({} as never) : undefined) };
  const absent = { getContract: async () => undefined };

  it('is sponsored only where the registry says so', async () => {
    expect(await feeModeOf('sponsored', deployed, sponsor)).toBe('sponsored');
    // Someone deployed a contract at the sponsor's address on a network that declares none: it is not trusted.
    expect(await feeModeOf('feeJuice', deployed, sponsor)).toBe('feeJuice');
    await expect(feeModeOf('sponsored', absent, sponsor)).rejects.toThrow('none is deployed');
  });

  it('reads anything but "sponsored" as no sponsor', () => {
    expect(declaredFees('sponsored')).toBe('sponsored');
    expect([undefined, 'feeJuice', 'free'].map(declaredFees)).toEqual(['feeJuice', 'feeJuice', 'feeJuice']);
  });
});
