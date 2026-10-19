// Wallet side of the dApp connection (wallet-sdk extension protocol): the role an extension's background script
// plays. The app's browser injects dapp/content_script.ts into pages and relays its messages here, with the page's
// origin taken from the native web view (never from the page).
//
// Policy: nothing reaches the PXE before the user approved the connection and the requested capabilities. Every
// transaction (sendTx), authorization (createAuthWit), and any batch containing one needs its own approval.
// Reads and simulations are allowed once capabilities are granted. Approvals are native panels docked under the
// page, which stays usable above them. The emojis are compared once, right after the key exchange.
import { BackgroundConnectionHandler, type ActiveSession, type PendingDiscovery } from '@aztec/wallet-sdk/extension/handlers';
import { hashToEmoji } from '@aztec/wallet-sdk/crypto';
import type { WalletMessage } from '@aztec/wallet-sdk/types';
import { type Bridge, errorMessage } from '@azwall/pxe-shared';

import { log } from '../log';
import type { NetworkParams } from '../open';
import type { AzwallWallet } from '../wallet';
import { batchNeedsApproval, describeMessage, NEEDS_APPROVAL } from './describe';
import { rawCall } from './raw_call';

const WALLET = { walletId: 'azwall', walletName: 'Azwall', walletVersion: '0.1.0' };

export type ApprovalKind = 'connect' | 'verify' | 'capabilities' | 'transaction' | 'authwit';
type Approval = { kind: ApprovalKind; origin: string; title: string; details: string[]; emojis?: string };
type ContentListener = (message: unknown, sender: { tab: { id: number; url: string } }) => void;
type ChainLike = { chainId?: { toString(): string }; version?: { toString(): string } } | undefined;

export class DappGateway {
  readonly #handler: BackgroundConnectionHandler;
  readonly #granted = new Map<string, unknown[]>();
  #account = '';
  #listener: ContentListener | null = null;

  constructor(
    private readonly bridge: Bridge,
    private readonly wallet: AzwallWallet,
    private readonly network: NetworkParams,
  ) {
    this.#handler = new BackgroundConnectionHandler(
      { ...WALLET, logger: { debug: () => {}, info: m => log('dapp', m), warn: m => log('dapp', m, 'warn'), error: m => log('dapp', m, 'error') } },
      {
        sendToTab: (tabId, message) => bridge.emit('dapp', { tabId, message }),
        addContentListener: listener => {
          this.#listener = listener as ContentListener;
        },
      },
      {
        onPendingDiscovery: d => void this.#discover(d),
        onSessionEstablished: session => void this.#verify(session),
        onSessionTerminated: id => {
          this.#granted.delete(id);
          this.#publishSessions();
        },
        onWalletMessage: (session, message) => void this.#handle(session, message),
      },
    );
    this.#handler.initialize();
  }

  /** A message from the content script of the page at `origin`, acting for the account `account`. */
  receive(tabId: number, origin: string, message: unknown, account: string): void {
    this.#account = account;
    this.#listener?.(message, { tab: { id: tabId, url: origin } });
  }

  disconnect(sessionId: string): void {
    this.#handler.terminateSession(sessionId);
  }

  #sameChain(c: ChainLike): boolean {
    const e = this.network.expected;
    // chainId and version are fields (Fr): their string form is hex, so compare numerically.
    const n = (v: { toString(): string } | undefined) => (v === undefined ? undefined : BigInt(v.toString()));
    return !!c && n(c.chainId) === BigInt(e.l1ChainId) && n(c.version) === BigInt(e.rollupVersion);
  }

  #ask(approval: Approval): Promise<boolean> {
    return this.bridge.callNative<{ approved: boolean }>('approve', approval).then(r => r.approved === true, () => false);
  }

  async #discover(d: PendingDiscovery): Promise<void> {
    if (!this.#sameChain(d.chainInfo as ChainLike)) {
      log('dapp', `${d.origin} asked for another network; ignored`, 'warn');
      this.#handler.rejectDiscovery(d.requestId);
      return;
    }
    const name = d.appName ?? d.appId;
    const ok = await this.#ask({ kind: 'connect', origin: d.origin, title: `Connect to ${name}?`, details: [`App id: ${d.appId}`] });
    if (ok) this.#handler.approveDiscovery(d.requestId);
    else this.#handler.rejectDiscovery(d.requestId);
  }

  /**
   * Right after the key exchange the page shows its emojis and asks the user to compare them with the wallet's:
   * show the wallet's at once, over the page. A mismatch means someone is in between: the session is dropped.
   */
  async #verify(session: ActiveSession): Promise<void> {
    this.#publishSessions();
    const ok = await this.#ask({
      kind: 'verify',
      origin: session.origin,
      title: 'Check the emojis',
      details: [],
      emojis: hashToEmoji(session.verificationHash),
    });
    if (!ok) this.#handler.terminateSession(session.sessionId);
  }

  async #handle(session: ActiveSession, m: WalletMessage): Promise<void> {
    const respond = (r: { result?: unknown; error?: { message: string } }) =>
      this.#handler.sendResponse(session.sessionId, { ...r, messageId: m.messageId, walletId: WALLET.walletId });
    try {
      if (!this.#sameChain(m.chainInfo as ChainLike)) throw new Error(`the wallet is on ${this.network.id}`);
      await respond({ result: await this.#dispatch(session, m) });
    } catch (err) {
      log('dapp', `${session.origin} ${m.type}: ${errorMessage(err)}`, 'warn');
      await respond({ error: { message: errorMessage(err) } });
    }
  }

  async #dispatch(session: ActiveSession, m: WalletMessage): Promise<unknown> {
    const args = (m.args ?? []) as unknown[];
    if (m.type === 'requestCapabilities') return this.#capabilities(session, args[0]);
    if (!this.#granted.has(session.sessionId)) throw new Error('call requestCapabilities first');
    if (m.type === 'getAccounts') return [{ alias: 'main', item: this.#account }];
    if (NEEDS_APPROVAL.has(m.type) || (m.type === 'batch' && batchNeedsApproval(args))) {
      const authwit = m.type === 'createAuthWit';
      const ok = await this.#ask({
        kind: authwit ? 'authwit' : 'transaction',
        origin: session.origin,
        title: authwit ? 'Authorize an action on your behalf?' : 'Prove and send this transaction?',
        details: describeMessage(m.type, args),
      });
      if (!ok) throw new Error('the user rejected the request');
    }
    return rawCall(this.wallet, m.type, args);
  }

  async #capabilities(session: ActiveSession, manifest: unknown): Promise<unknown> {
    const m = (manifest ?? {}) as { capabilities?: { type: string }[]; metadata?: { name?: string } };
    const asked = m.capabilities ?? [];
    const ok = await this.#ask({
      kind: 'capabilities',
      origin: session.origin,
      title: `${m.metadata?.name ?? session.appId} asks for access`,
      details: asked.map(c => c.type),
    });
    if (!ok) throw new Error('the user refused the requested capabilities');
    const account = { alias: 'main', item: this.#account };
    const caps = asked.map(c => (c.type === 'accounts' ? { ...c, accounts: [account] } : c));
    this.#granted.set(session.sessionId, caps);
    this.#publishSessions();
    return { version: '1.0', granted: caps, wallet: { name: WALLET.walletName, version: WALLET.walletVersion } };
  }

  /** The connected sites, for the app's list of connections. */
  #publishSessions(): void {
    const sessions = this.#handler.getActiveSessions().map(s => ({
      sessionId: s.sessionId,
      origin: s.origin,
      appId: s.appId,
      emojis: hashToEmoji(s.verificationHash),
      granted: this.#granted.has(s.sessionId),
    }));
    this.bridge.emit('dappSessions', sessions);
  }
}
