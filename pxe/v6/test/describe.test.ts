import { describe, expect, it } from 'vitest';

import { batchNeedsApproval, describeCall, describeMessage, txHashOf } from '../src/dapp/describe';

describe('describe', () => {
  it('names calls by function, kind and short target', () => {
    expect(describeCall({ name: 'transfer', type: 'private', to: '0x1234567890abcdef1234' })).toBe('transfer (private) on 0x123456…1234');
    expect(describeCall(null)).toBe('unknown function on ?');
  });

  it('lists the calls of a transaction and its authorizations', () => {
    const lines = describeMessage('sendTx', [{ calls: [{ name: 'cast_vote', to: '0xab' }], authWitnesses: [{}] }]);
    expect(lines).toEqual(['cast_vote on 0xab', '1 authorization(s) attached']);
    expect(describeMessage('getAccounts', [])).toEqual([]);
  });

  it('flags batches that contain a transaction', () => {
    expect(batchNeedsApproval([[{ name: 'simulateTx' }, { name: 'sendTx' }]])).toBe(true);
    expect(batchNeedsApproval([[{ name: 'simulateTx' }]])).toBe(false);
    expect(batchNeedsApproval(['nope'])).toBe(false);
  });

  it('finds the hash of the transaction a call sent', () => {
    const hash = `0x${'ab'.repeat(32)}`;
    const txHash = { toString: () => hash };
    expect(txHashOf(txHash)).toBe(hash);
    expect(txHashOf({ txHash, blockNumber: 5 })).toBe(hash);
    expect(txHashOf([{ name: 'simulateTx', result: 1 }, { name: 'sendTx', result: txHash }])).toBe(hash);
    expect(txHashOf({ status: 'ok' })).toBeUndefined();
    expect(txHashOf(undefined)).toBeUndefined();
  });
});
