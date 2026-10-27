import { describe, expect, it, vi } from 'vitest';

import { tokenHistory } from '../src/history';
import { log } from '../src/log';
import type { AzwallWallet } from '../src/wallet';

vi.mock('../src/log', () => ({ log: vi.fn() }));

const owner = `0x${'01'.repeat(32)}`;
const token = `0x${'02'.repeat(32)}`;

describe('tokenHistory', () => {
  it('keeps a move whose block the node cannot read, at time 0, and logs why', async () => {
    const event = {
      event: { from: owner, to: token, amount: 5n },
      metadata: { txHash: '0xabc', l2BlockNumber: 7 },
    };
    const wallet = {
      getPrivateEvents: async () => [event],
      node: { getBlock: async () => Promise.reject(new Error('node unreachable')) },
    } as unknown as AzwallWallet;
    const moves = await tokenHistory(wallet, owner, [token]);
    expect(moves).toEqual([{ token, txHash: '0xabc', block: 7, at: 0, from: owner, to: token, amount: '5' }]);
    expect(log).toHaveBeenCalledWith('history', 'reading block 7: node unreachable', 'warn');
  });
});
