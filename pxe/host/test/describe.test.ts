import { describe, expect, it } from 'vitest';

import { txHashOf } from '../src/dapp/describe';

describe('describe', () => {
  it('finds the hash of the transaction a call sent', () => {
    const hash = `0x${'ab'.repeat(32)}`;
    const txHash = { toString: () => hash };
    expect(txHashOf(txHash)).toBe(hash);
    expect(txHashOf({ txHash, blockNumber: 5 })).toBe(hash);
    // The SDK's default: sendTx waited, and returns the receipt (2026-10-28 store-release audit, F8).
    expect(txHashOf({ receipt: { txHash, status: 'proposed' } })).toBe(hash);
    expect(txHashOf([{ name: 'simulateTx', result: 1 }, { name: 'sendTx', result: txHash }])).toBe(hash);
    expect(txHashOf({ status: 'ok' })).toBeUndefined();
    expect(txHashOf(undefined)).toBeUndefined();
  });
});
