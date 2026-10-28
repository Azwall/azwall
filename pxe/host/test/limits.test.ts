import { describe, expect, it } from 'vitest';

import { messageFrom, SigningGate, STRANGER_MESSAGE } from '../src/dapp/limits';

describe('what a page may cost the wallet', () => {
  it('parses a large message only from a page with a session', () => {
    const large = JSON.stringify({ artifact: 'x'.repeat(STRANGER_MESSAGE) });
    expect(messageFrom(large, () => false)).toBeUndefined();
    expect(messageFrom(large, () => true)).toEqual({ artifact: 'x'.repeat(STRANGER_MESSAGE) });
    // A discovery is small: parsed without asking about sessions.
    expect(messageFrom('{"type":"aztec-wallet-discovery"}', () => { throw new Error('not asked'); })).toEqual({ type: 'aztec-wallet-discovery' });
  });

  it('lets a site have one signing request waiting, and another once it is answered', async () => {
    const gate = new SigningGate();
    let answer!: () => void;
    const first = gate.run('s1', () => new Promise<void>(r => (answer = r)));
    await expect(gate.run('s1', async () => {})).rejects.toThrow('waiting for your approval');
    await expect(gate.run('s2', async () => 'other site')).resolves.toBe('other site');
    answer();
    await first;
    await expect(gate.run('s1', async () => 'next')).resolves.toBe('next');
  });
});
