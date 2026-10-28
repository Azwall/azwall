import { TxStatus } from '@aztec/stdlib/tx';
import { describe, expect, it } from 'vitest';

import { confirmSubmissions } from '../src/submit';

const tx = { getTxHash: () => ({ toString: () => '0xabc' }) };

/** A node whose submission fails, and whose receipt reads give `answers` in turn (an Error: the read fails). */
function node(answers: (TxStatus | Error)[], told: string[] = []) {
  const n = {
    sendTx: async (_tx: typeof tx): Promise<void> => {
      throw new Error('fetch failed');
    },
    getTxReceipt: async () => {
      const answer = answers.shift() ?? TxStatus.DROPPED;
      if (answer instanceof Error) throw answer;
      return { status: answer };
    },
  };
  confirmSubmissions(n, { pause: async () => {}, submitting: hash => told.push(hash) });
  return n;
}

describe('a failed submission', () => {
  it('counts as sent when the node has the transaction', async () => {
    await expect(node([TxStatus.DROPPED, TxStatus.PENDING]).sendTx(tx)).resolves.toBeUndefined();
  });

  it('fails when the node says it never saw it', async () => {
    await expect(node([]).sendTx(tx)).rejects.toThrow('fetch failed');
  });

  it('counts as sent when nobody can say, so the app follows it by its hash instead of sending it again', async () => {
    const offline = () => new Error('offline');
    await expect(node([offline(), offline(), offline(), TxStatus.PROPOSED]).sendTx(tx)).resolves.toBeUndefined();
  });

  it('fails when the node answered "dropped" once, however the other reads went', async () => {
    await expect(node([new Error('offline'), TxStatus.DROPPED, new Error('offline')]).sendTx(tx)).rejects.toThrow('fetch failed');
  });
});

describe('a submission', () => {
  it('tells its hash before the transaction is sent', async () => {
    const told: string[] = [];
    const n = node([], told);
    await n.sendTx(tx).catch(() => undefined);
    expect(told).toEqual(['0xabc']);
  });
});
