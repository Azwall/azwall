import { TxStatus } from '@aztec/stdlib/tx';
import { describe, expect, it } from 'vitest';

import { confirmSubmissions } from '../src/submit';

const tx = { getTxHash: () => ({ toString: () => '0xabc' }) };

function node(statuses: TxStatus[]) {
  const n = {
    sendTx: async (_tx: typeof tx): Promise<void> => {
      throw new Error('fetch failed');
    },
    getTxReceipt: async () => ({ status: statuses.shift() ?? TxStatus.DROPPED }),
  };
  confirmSubmissions(n, async () => {});
  return n;
}

describe('a failed submission', () => {
  it('counts as sent when the node has the transaction', async () => {
    await expect(node([TxStatus.DROPPED, TxStatus.PENDING]).sendTx(tx)).resolves.toBeUndefined();
  });

  it('fails when the node never saw it', async () => {
    await expect(node([]).sendTx(tx)).rejects.toThrow('fetch failed');
  });
});
