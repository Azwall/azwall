// A transaction whose submission failed may still have reached the node: the request got there and only its answer
// was lost (a timeout, a reset connection). Reported as failed, it would be sent again by the user, and paid twice.
// So a failed submission asks the node about the transaction before giving up on it, and one nobody can answer for
// counts as sent: the app follows it by its hash until a receipt settles it. Its hash is also told before it is sent,
// so a wallet that stops mid-send still knows what to look for.
import { TxStatus } from '@aztec/stdlib/tx';

import { log } from './log';

type Submitted = { getTxHash(): { toString(): string } };
type NodeLike<T extends Submitted> = {
  sendTx(tx: T): Promise<void>;
  getTxReceipt(hash: ReturnType<T['getTxHash']>): Promise<{ status: TxStatus }>;
};

export type SubmitOptions = {
  pause?: (ms: number) => Promise<unknown>;
  /** Told the hash of each transaction just before it is sent. */
  submitting?: (txHash: string) => void;
};

const ASKS = 3;
const PAUSE_MS = 2_000;

/** Wraps `node.sendTx` so that a transaction the node did receive, or may have, counts as sent, whatever its answer did. */
export function confirmSubmissions<T extends Submitted>(node: NodeLike<T>, opts: SubmitOptions = {}): void {
  const pause = opts.pause ?? ((ms: number) => new Promise(r => setTimeout(r, ms)));
  const send = node.sendTx.bind(node);
  node.sendTx = async (tx: T) => {
    const hash = tx.getTxHash() as ReturnType<T['getTxHash']>;
    opts.submitting?.(hash.toString());
    try {
      await send(tx);
    } catch (err) {
      const why = err instanceof Error ? err.message : String(err);
      let answered = false;
      for (let ask = 0; ask < ASKS; ask++) {
        await pause(PAUSE_MS);
        // A read that fails too is no answer: the next ask.
        const receipt = await node.getTxReceipt(hash).catch(() => undefined);
        if (!receipt) continue;
        answered = true;
        if (receipt.status !== TxStatus.DROPPED) {
          log('tx', `the node received ${hash.toString().slice(0, 10)}… although sending it failed (${why})`, 'warn');
          return;
        }
      }
      if (answered) throw err;
      log('tx', `nobody could say whether ${hash.toString().slice(0, 10)}… reached the node (${why}): followed as sent`, 'warn');
    }
  };
}
