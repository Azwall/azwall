// Wallet side of the dApp connection (wallet-sdk extension protocol): the role an extension's background script
// plays. The app's browser injects dapp/content_script.ts into pages and relays its messages here, with the page's
// origin taken from the native web view (never from the page) and a page id the app gives each page load.
//
// Policy: nothing reaches the PXE before the user approved the connection, compared the emojis and approved the
// requested capabilities. A session belongs to the page that opened it: messages from another page or origin are
// dropped, and the app closes a page's sessions when it navigates away. A session acts as the one account selected
// when its capabilities were granted (policy.ts). Every transaction (sendTx) and authorization (createAuthWit), also
// inside a batch, needs its own approval, which shows the functions the calls really run (calls.ts).
import { BackgroundConnectionHandler, type ActiveSession, type PendingDiscovery } from '@aztec/wallet-sdk/extension/handlers';
import { hashToEmoji } from '@aztec/wallet-sdk/crypto';
import type { WalletMessage } from '@aztec/wallet-sdk/types';
import { type Bridge, errorMessage } from '@azwall/pxe-shared';

import { log } from '../log';
import type { NetworkParams } from '../open';
import type { AzwallWallet } from '../wallet';
import { describeAuthWit, describeTx } from './calls';
import { NEEDS_APPROVAL, txHashOf } from './describe';
import { confine, samePage } from './policy';
import { rawCall } from './raw_call';

const WALLET = { walletId: 'azwall', walletName: 'Azwall', walletVersion: '0.1.0' };

export type ApprovalKind = 'connect' | 'verify' | 'capabilities' | 'transaction' | 'authwit';
/** `tab` is the page id: the app drops a page's pending approvals when it closes. */
type Approval = { kind: ApprovalKind; tab: number; origin: string; title: string; details: string[]; emojis?: string };
type Op = { name: string; args: unknown[] };
type ContentListener = (message: unknown, sender: { tab: { id: number; url: string } }) => void;
type ChainLike = { chainId?: { toString(): string }; version?: { toString(): string } } | undefined;

export class DappGateway {
  readonly #handler: BackgroundConnectionHandler;
  readonly #granted = new Map<string, unknown[]>();
  /** The account each session acts as: the one selected when its capabilities were granted. */
  readonly #accounts = new Map<string, string>();
  /** Each session's emoji check: no wallet message is served before the user confirmed it. */
  readonly #verified = new Map<string, Promise<boolean>>();
  /** Pages with a connection prompt open: a page cannot stack them. */
  readonly #connecting = new Set<number>();
  #selected = '';
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
        onSessionEstablished: session => this.#verify(session),
        onSessionTerminated: id => {
          this.#granted.delete(id);
          this.#accounts.delete(id);
          this.#verified.delete(id);
          this.#publishSessions();
        },
        onWalletMessage: (session, message) => void this.#handle(session, message),
      },
    );
    this.#handler.initialize();
  }

  /**
   * A message from the content script of page `tabId` at `origin`, while `account` is selected in the app. A
   * message for a session or discovery of another page, or of another origin, is dropped.
   */
  receive(tabId: number, origin: string, message: unknown, account: string): void {
    this.#selected = account;
    const sessionId = (message as { sessionId?: unknown } | null)?.sessionId;
    if (typeof sessionId === 'string') {
      const owner = this.#handler.getSession(sessionId) ?? this.#handler.getPendingDiscovery(sessionId);
      if (!samePage(owner, tabId, origin)) {
        log('dapp', `${origin} used a session of another page; dropped`, 'warn');
        return;
      }
    }
    this.#listener?.(message, { tab: { id: tabId, url: origin } });
  }

  /** The page navigated away or its tab closed: its sessions and pending connections end. */
  closePage(tabId: number): void {
    this.#handler.terminateForTab(tabId);
    this.#connecting.delete(tabId);
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
    if (!this.#sameChain(d.chainInfo as ChainLike) || this.#connecting.has(d.tabId)) {
      log('dapp', `${d.origin} asked for another network, or again; ignored`, 'warn');
      this.#handler.rejectDiscovery(d.requestId);
      return;
    }
    this.#connecting.add(d.tabId);
    // The page names itself: shown as a claim under the origin, which the app shows first.
    const ok = await this.#ask({
      kind: 'connect',
      tab: d.tabId,
      origin: d.origin,
      title: 'Connect this site to your wallet?',
      details: [`It calls itself "${d.appName ?? d.appId}"`],
    });
    this.#connecting.delete(d.tabId);
    if (ok) this.#handler.approveDiscovery(d.requestId);
    else this.#handler.rejectDiscovery(d.requestId);
  }

  /**
   * Right after the key exchange the page shows its emojis and asks the user to compare them with the wallet's:
   * show the wallet's at once, over the page. A mismatch means someone is in between: the session is dropped.
   */
  #verify(session: ActiveSession): void {
    this.#publishSessions();
    const check = this.#ask({
      kind: 'verify',
      tab: session.tabId,
      origin: session.origin,
      title: 'Check the emojis',
      details: [],
      emojis: hashToEmoji(session.verificationHash),
    });
    this.#verified.set(session.sessionId, check);
    void check.then(ok => ok || this.#handler.terminateSession(session.sessionId));
  }

  async #handle(session: ActiveSession, m: WalletMessage): Promise<void> {
    const respond = (r: { result?: unknown; error?: { message: string } }) =>
      this.#handler.sendResponse(session.sessionId, { ...r, messageId: m.messageId, walletId: WALLET.walletId });
    try {
      if (!(await this.#verified.get(session.sessionId))) throw new Error('the emojis were not confirmed');
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
    const account = this.#accounts.get(session.sessionId);
    if (!account) throw new Error('call requestCapabilities first');
    if (m.type !== 'batch') return this.#approved(session, account, [{ name: m.type, args }]).then(([r]) => r);
    const ops = (Array.isArray(args[0]) ? args[0] : []).map(b => b as Op);
    const results = await this.#approved(session, account, ops);
    return ops.map((op, i) => ({ name: op.name, result: results[i] }));
  }

  /**
   * Runs `ops` for `session` as `account`, after one approval listing every transaction and authorization among
   * them. Arguments are confined to the account first, so the approval shows what will run.
   */
  async #approved(session: ActiveSession, account: string, ops: Op[]): Promise<unknown[]> {
    const others = (await this.wallet.getAccounts()).map(a => a.item.toString()).filter(a => a.toLowerCase() !== account.toLowerCase());
    const confined = ops.map(op => ({ name: String(op.name), args: Array.isArray(op.args) ? op.args : [] }));
    for (const op of confined) {
      if (op.name === 'batch' || op.name === 'requestCapabilities') throw new Error(`${op.name} cannot be batched`);
      if (op.name !== 'getAccounts' && op.name !== 'getAddressBook') op.args = confine(op.name, op.args, account, others);
    }
    const signing = confined.filter(op => NEEDS_APPROVAL.has(op.name));
    if (signing.length) {
      const details = (await Promise.all(signing.map(op => this.#describe(op)))).flat();
      const authwit = signing.every(op => op.name === 'createAuthWit');
      const title = authwit ? 'Authorize an action on your behalf?' : 'Prove and send this transaction?';
      if (!(await this.#ask({ kind: authwit ? 'authwit' : 'transaction', tab: session.tabId, origin: session.origin, title, details }))) {
        throw new Error('the user rejected the request');
      }
    }
    const results: unknown[] = [];
    for (const op of confined) results.push(await this.#run(session, account, op));
    return results;
  }

  #describe(op: Op): Promise<string[]> {
    const artifactOf = (address: string) => this.wallet.artifactAt(address);
    return op.name === 'sendTx' ? describeTx(op.args[0], artifactOf) : describeAuthWit(op.args[1], artifactOf);
  }

  async #run(session: ActiveSession, account: string, op: Op): Promise<unknown> {
    if (op.name === 'getAccounts') return [{ alias: 'main', item: account }];
    // The wallet's contacts are not the site's business.
    if (op.name === 'getAddressBook') return [];
    if (op.name !== 'sendTx') return rawCall(this.wallet, op.name, op.args);
    return this.#send(session, account, op.args);
  }

  /**
   * An approved transaction, reported to the app as it goes (`dappTx` events) so it lands in the wallet's
   * activity like the wallet's own: proving, then sent with its hash (the app follows it to a block) or failed.
   */
  async #send(session: ActiveSession, account: string, args: unknown[]): Promise<unknown> {
    const summary = (await describeTx(args[0], a => this.wallet.artifactAt(a)).catch(() => []))[0] ?? 'sendTx';
    const tx = { id: crypto.randomUUID(), account, origin: session.origin, summary };
    this.bridge.emit('dappTx', { ...tx, status: 'proving' });
    try {
      const result = await rawCall(this.wallet, 'sendTx', args);
      this.bridge.emit('dappTx', { ...tx, status: 'sent', txHash: txHashOf(result) });
      return result;
    } catch (err) {
      this.bridge.emit('dappTx', { ...tx, status: 'failed', error: errorMessage(err) });
      throw err;
    }
  }

  async #capabilities(session: ActiveSession, manifest: unknown): Promise<unknown> {
    const m = (manifest ?? {}) as { capabilities?: { type: string }[]; metadata?: { name?: string } };
    const asked = m.capabilities ?? [];
    const claim = m.metadata?.name ? [`It calls itself "${m.metadata.name}"`] : [];
    const account = this.#selected;
    const ok = await this.#ask({
      kind: 'capabilities',
      tab: session.tabId,
      origin: session.origin,
      title: 'Let this site use your account?',
      details: [...asked.map(c => c.type), ...claim],
    });
    if (!ok) throw new Error('the user refused the requested capabilities');
    const caps = asked.map(c => (c.type === 'accounts' ? { ...c, accounts: [{ alias: 'main', item: account }] } : c));
    this.#granted.set(session.sessionId, caps);
    this.#accounts.set(session.sessionId, account);
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
