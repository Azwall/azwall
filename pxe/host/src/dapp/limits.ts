// What a page may cost the wallet before and after it connects. Any https page can post messages to the wallet; each
// is parsed in the PXE's page, and each signing request is simulated before the user sees it.

/**
 * The largest message a page may send before it has a session: a discovery or key exchange is small. Larger ones (a
 * contract artifact to register, several megabytes) only from a connected site.
 */
export const STRANGER_MESSAGE = 64_000;

/** The message in `text`, or undefined when it is too large for a page without a session (`connected`). */
export function messageFrom(text: string, connected: () => boolean): unknown {
  if (text.length > STRANGER_MESSAGE && !connected()) return undefined;
  return JSON.parse(text);
}

/** One signing request waiting for the user per session: a site cannot queue them up, each with its simulation. */
export class SigningGate {
  readonly #waiting = new Set<string>();

  async run<T>(sessionId: string, review: () => Promise<T>): Promise<T> {
    if (this.#waiting.has(sessionId)) throw new Error('another request from this site is waiting for your approval');
    this.#waiting.add(sessionId);
    try {
      return await review();
    } finally {
      this.#waiting.delete(sessionId);
    }
  }
}
