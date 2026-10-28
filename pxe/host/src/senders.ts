// EmbeddedWallet.sendTx signs, without asking, an authorization for every one its simulation requests, on behalf of
// whichever account the request names. A transaction from one account must never spend another's: while
// transactions are being sent, an authorization may only be signed for one of their senders.

export class SenderGuard {
  readonly #sending: string[] = [];

  /** Runs the sending of a transaction from `from`. */
  async sending<T>(from: string, send: () => Promise<T>): Promise<T> {
    const who = from.toLowerCase();
    this.#sending.push(who);
    try {
      return await send();
    } finally {
      this.#sending.splice(this.#sending.indexOf(who), 1);
    }
  }

  /** Throws when an authorization for `onBehalfOf` would be signed for a transaction another account sends. */
  check(onBehalfOf: string): void {
    if (this.#sending.length && !this.#sending.includes(onBehalfOf.toLowerCase())) {
      throw new Error(`a transaction asked to authorize an action for another account (${onBehalfOf.slice(0, 10)}…): refused`);
    }
  }
}
