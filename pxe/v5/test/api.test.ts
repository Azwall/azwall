import { describe, expect, it } from 'vitest';

import { Bridge } from '@azwall/pxe-shared';

import { createHandlers } from '../src/api';
import { compatibilityProblems, type NetworkParams } from '../src/open';
import { addr } from '../src/token';

const network: NetworkParams = {
  id: 'testnet-v6',
  rpcUrl: 'https://node.example',
  rpcMaxBatch: 3,
  aztec: '5.1.0',
  expected: { l1ChainId: 11155111, rollupVersion: 1821665230, vkTreeRoot: '0xaa', protocolContractsHash: '0xbb' },
};

const handlers = () => createHandlers(new Bridge({ send: () => {} }, {}));

describe('compatibilityProblems', () => {
  const ok = { nodeVersion: '5.1.0', l1ChainId: 11155111, rollupVersion: 1821665230, vkTreeRoot: '0xaa', protocolContractsHash: '0xbb' };

  it('accepts a matching node and client', () => {
    expect(compatibilityProblems(ok, network)).toEqual([]);
  });

  it('lists every mismatch', () => {
    const bad = { nodeVersion: '4.0.0', l1ChainId: 1, rollupVersion: 2, vkTreeRoot: '0x01', protocolContractsHash: '0x02' };
    expect(compatibilityProblems(bad, network)).toHaveLength(5);
  });
});

describe('addr', () => {
  it('accepts 32-byte hex addresses only', () => {
    expect(addr(`0x${'12'.repeat(32)}`).toString()).toBe(`0x${'12'.repeat(32)}`);
    expect(() => addr('0x1234')).toThrow('not an Aztec address');
  });
});

describe('handlers', () => {
  it('refuse wallet calls before open', async () => {
    await expect(handlers().blockNumber({})).rejects.toThrow('the PXE is not open');
  });

  it('validate parameters before touching the network', async () => {
    const h = handlers();
    await expect(h.open({ network: { ...network, rpcMaxBatch: -1 }, dbKey: `0x${'00'.repeat(32)}` })).rejects.toThrow('rpcMaxBatch');
    await expect(h.open({ network, dbKey: '0x00' })).rejects.toThrow('dbKey must be 32 bytes');
    await expect(h.transfer({ kind: 'teleport' })).rejects.toThrow();
    await expect(h.open('nope')).rejects.toThrow('parameters must be an object');
  });

  it('generate valid signing keys', async () => {
    const { signingKey } = (await handlers().newSigningKey({})) as { signingKey: string };
    expect(signingKey).toMatch(/^0x[0-9a-f]{64}$/);
  });
});
