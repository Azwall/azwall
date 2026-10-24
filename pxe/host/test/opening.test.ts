import { describe, expect, it, vi } from 'vitest';

import { Bridge } from '@azwall/pxe-shared';

import { createHandlers } from '../src/api';
import { type NetworkParams } from '../src/open';
import { AZTEC_VERSION } from '../src/version';

// An open that never ends, like one syncing again from the chain after its stores were set aside.
vi.mock('../src/open', async original => ({ ...(await original<typeof import('../src/open')>()), openWallet: () => new Promise(() => {}) }));

const network: NetworkParams = {
  id: 'testnet',
  rpcUrl: 'https://node.example',
  rpcMaxBatch: 3,
  aztec: AZTEC_VERSION,
  fees: 'sponsored',
  expected: { l1ChainId: 11155111, rollupVersion: 3409706519 },
};

describe('handlers while the PXE opens', () => {
  it('say the wallet is still loading, and refuse a second open', async () => {
    const h = createHandlers(new Bridge({ send: () => {} }, {}));
    void h.open({ network, dbKey: `0x${'00'.repeat(32)}` });
    await Promise.resolve();
    await expect(h.blockNumber({})).rejects.toThrow('still loading');
    await expect(h.open({ network, dbKey: `0x${'00'.repeat(32)}` })).rejects.toThrow('already open');
  });
});
