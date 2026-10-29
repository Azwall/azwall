// EmbeddedWallet.sendTx signs, without asking, an authorization for every one its simulation requests, on behalf of
// whichever account the request names. A transaction from one account must never spend another's: while a
// transaction is being sent, an authorization may only be signed for its sender. Sends run one at a time, so "its
// sender" is never ambiguous: with two in flight, one account's simulation could ask for the other's authorization
// and pass as one of the senders.

export class SenderGuard {
  #sender: string | undefined;
  #label: string | undefined;
  #queue: Promise<unknown> = Promise.resolve();

  /** The account whose transaction is being sent, if any. */
  get current(): string | undefined {
    return this.#sender;
  }

  /** The label of the send under way: the dApp transaction it is (its id in the app's history), if any. */
  get label(): string | undefined {
    return this.#label;
  }

  /** Runs the sending of a transaction from `from`, once every send started before it has ended. */
  sending<T>(from: string, send: () => Promise<T>, label?: string): Promise<T> {
    const turn = this.#queue.then(async () => {
      this.#sender = from.toLowerCase();
      this.#label = label;
      try {
        return await send();
      } finally {
        this.#sender = undefined;
        this.#label = undefined;
      }
    });
    // Its caller gets its failure; the next send only waits for it to end.
    this.#queue = turn.catch(() => undefined);
    return turn;
  }

  /** Throws when an authorization for `onBehalfOf` would be signed for a transaction another account sends. */
  check(onBehalfOf: string): void {
    if (this.#sender !== undefined && this.#sender !== onBehalfOf.toLowerCase()) {
      throw new Error(`a transaction asked to authorize an action for another account (${onBehalfOf.slice(0, 10)}…): refused`);
    }
  }
}
