// A transaction whose submission failed may still have reached the node: the request got there and only its answer
// was lost (a timeout, a reset connection). Reported as failed, it would be sent again by the user, and paid twice.
// So a failed submission asks the node about the transaction before giving up on it.
import { TxStatus } from '@aztec/stdlib/tx';

import { log } from './log';

type Submitted = { getTxHash(): { toString(): string } };
type NodeLike<T extends Submitted> = {
  sendTx(tx: T): Promise<void>;
  getTxReceipt(hash: ReturnType<T['getTxHash']>): Promise<{ status: TxStatus }>;
};

const ASKS = 3;
const PAUSE_MS = 2_000;

/** Wraps `node.sendTx` so that a transaction the node did receive counts as sent, whatever its answer did. */
export function confirmSubmissions<T extends Submitted>(node: NodeLike<T>, pause = (ms: number) => new Promise(r => setTimeout(r, ms))): void {
  const send = node.sendTx.bind(node);
  node.sendTx = async (tx: T) => {
    try {
      await send(tx);
    } catch (err) {
      const hash = tx.getTxHash() as ReturnType<T['getTxHash']>;
      for (let ask = 0; ask < ASKS; ask++) {
        await pause(PAUSE_MS);
        // A read that fails too is no answer: the next ask, or the original error.
        const receipt = await node.getTxReceipt(hash).catch(() => undefined);
        if (receipt && receipt.status !== TxStatus.DROPPED) {
          log('tx', `the node received ${hash.toString().slice(0, 10)}… although sending it failed (${err instanceof Error ? err.message : String(err)})`, 'warn');
          return;
        }
      }
      throw err;
    }
  };
}
