// Wallet side of the dApp connection (wallet-sdk extension protocol): the role an extension's background script
// plays. The app's browser injects dapp/content_script.ts into pages and relays its messages here, with the page's
// origin taken from the native web view (never from the page) and a page id the app gives each page load.
//
// Policy: nothing reaches the PXE before the user approved the connection, compared the emojis and approved the
// requested capabilities. A session belongs to the page that opened it: messages from another page or origin are
// dropped, and the app closes a page's sessions when it navigates away. A session acts as the one account selected
// when its capabilities were granted (policy.ts), and only within those capabilities (grants.ts). Every transaction
// (sendTx) and authorization (createAuthWit), also inside a batch, needs its own approval, which shows the functions
// the calls really run (calls.ts).
import { BackgroundConnectionHandler, type ActiveSession, type PendingDiscovery } from '@aztec/wallet-sdk/extension/handlers';
import { hashToEmoji } from '@aztec/wallet-sdk/crypto';
import { computeContractAddressFromInstance, type ContractInstancePreimage } from '@aztec/stdlib/contract';
import type { WalletMessage } from '@aztec/wallet-sdk/types';
import { type Bridge, errorMessage } from '@azwall/pxe-shared';

import { log } from '../log';
import type { NetworkParams } from '../open';
import type { AzwallWallet } from '../wallet';
import { describeAuthWit, describeTx, otherCallLines, readCall } from './calls';
import { authwitSections, capabilitySections, connectSections, rawSection, type Section, txSections } from './details';
import { NEEDS_APPROVAL, txHashOf } from './describe';
import { changeLines, feeLine, metaOf, type Payer, simulateEffects } from './effects';
import { added, addedLines, grantsOf, type Grants, merge, oneAtATime, permit } from './grants';
import { list, obj } from './json';
import { confine, samePage } from './policy';
import { markSiteRegistered } from './provenance';
import { canonicalArgs, parseArgs, rawCall } from './raw_call';

const WALLET = { walletId: 'azwall', walletName: 'Azwall', walletVersion: '0.8.0' };

export type ApprovalKind = 'connect' | 'verify' | 'capabilities' | 'transaction' | 'authwit';
/**
 * `tab` is the page id: the app drops a page's pending approvals when it closes. `details` is the summary the panel
 * shows; `sections` everything else, in full, for the view the panel opens (details.ts).
 */
type Approval = { kind: ApprovalKind; tab: number; origin: string; title: string; details: string[]; sections?: Section[]; emojis?: string };
type Op = { name: string; args: unknown[] };
type ContentListener = (message: unknown, sender: { tab: { id: number; url: string } }) => void;
type ChainLike = { chainId?: { toString(): string }; version?: { toString(): string } } | undefined;

/**
 * An op of a site's request in the form the wallet runs it (canonicalArgs): from here on, what the review shows, what
 * the checks allow and what runs are the same request.
 */
async function canonical(op: Op): Promise<Op> {
  const name = String(op.name);
  if (name === 'batch' || name === 'requestCapabilities') throw new Error(`${name} cannot be batched`);
  const args = Array.isArray(op.args) ? op.args : [];
  return { name, args: name === 'getAccounts' || name === 'getAddressBook' ? [] : await canonicalArgs(name, args) };
}

export class DappGateway {
  readonly #handler: BackgroundConnectionHandler;
  /** What each session's site may do, as the user accepted it (grants.ts). */
  readonly #granted = new Map<string, Grants>();
  /** Each session's capability requests, one at a time. */
  readonly #capabilityQueue = oneAtATime();
  /** The account each session acts as: the one selected when its capabilities were granted. */
  readonly #accounts = new Map<string, string>();
  /** Each session's emoji check: no wallet message is served before the user confirmed it. */
  readonly #verified = new Map<string, Promise<boolean>>();
  /** Pages with a connection prompt open: a page cannot stack them. */
  readonly #connecting = new Set<number>();
  /** Sessions with a signing request waiting for the user: each its own simulation first, so one at a time. */
  readonly #signing = new Set<string>();
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
  receive(tabId: number, origin: string, parsed: unknown, account: string, text: string): void {
    this.#selected = account;
    // Parsed already when small; a large one only for a page with a session (STRANGER_MESSAGE in api.ts).
    const message = parsed ?? this.#fromConnected(tabId, origin, text);
    if (message === undefined) {
      log('dapp', `${origin} sent a large message without a session; dropped`, 'warn');
      return;
    }
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

  /** A large message, parsed only when page `tabId` at `origin` holds a session. */
  #fromConnected(tabId: number, origin: string, text: string): unknown {
    const connected = this.#handler.getActiveSessions().some(s => samePage(s, tabId, origin));
    return connected ? JSON.parse(text) : undefined;
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
      sections: connectSections(d.origin, d.appId, d.appName, d.chainInfo),
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
    if (m.type === 'requestCapabilities') return this.#capabilityQueue(session.sessionId, () => this.#capabilities(session, args[0]));
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
    const confined = await Promise.all(ops.map(op => canonical(op)));
    for (const op of confined) {
      if (op.name !== 'getAccounts' && op.name !== 'getAddressBook') op.args = confine(op.name, op.args, account, others);
      await permit(this.#granted.get(session.sessionId) ?? grantsOf([]), op.name, op.args, a => this.wallet.artifactAt(a));
    }
    const signing = confined.filter(op => NEEDS_APPROVAL.has(op.name));
    if (signing.length) await this.#oneSigning(session.sessionId, () => this.#review(session, account, signing));
    const results: unknown[] = [];
    for (const op of confined) results.push(await this.#run(session, account, op));
    return results;
  }

  /** Runs `review` unless the session already has a signing request waiting: a site cannot queue them up. */
  async #oneSigning(sessionId: string, review: () => Promise<void>): Promise<void> {
    if (this.#signing.has(sessionId)) throw new Error('another request from this site is waiting for your approval');
    this.#signing.add(sessionId);
    try {
      await review();
    } finally {
      this.#signing.delete(sessionId);
    }
  }

  /** Asks the user to approve the signing ops; throws when they reject them. */
  async #review(session: ActiveSession, account: string, signing: Op[]): Promise<void> {
    const details = (await Promise.all(signing.map(op => this.#detailsOf(account, op)))).flat();
    const sections = await this.#sections(signing);
    const authwit = signing.every(op => op.name === 'createAuthWit');
    const title = authwit ? 'Authorize an action on your behalf?' : 'Prove and send this transaction?';
    const kind = authwit ? 'authwit' : 'transaction';
    if (!(await this.#ask({ kind, tab: session.tabId, origin: session.origin, title, details, sections }))) {
      throw new Error('the user rejected the request');
    }
  }

  /**
   * The summary of a transaction: what it takes from and gives to the account and its fee, by simulating it
   * (effects.ts). It replaces the list of calls, which stays in the details; without changes to show (or when the
   * simulation fails, which is said: the transaction would most likely fail too) the calls are listed after it.
   */
  async #summary(account: string, op: Op): Promise<{ lines: string[]; callsToo: boolean }> {
    try {
      const { changes, fee } = await simulateEffects(this.wallet, account, op.args);
      const meta = await metaOf(this.wallet, account, changes);
      const fees = feeLine(fee, await this.#payer(account, op.args));
      if (!changes.length) return { lines: ['No change to the balances of the tokens the wallet knows', fees], callsToo: true };
      const others = await otherCallLines(op.args[0], a => this.wallet.artifactAt(a), new Set(changes.map(c => c.token)));
      return { lines: [...changeLines(changes, meta), ...others, fees], callsToo: false };
    } catch (err) {
      log('dapp', `simulation before approval: ${errorMessage(err)}`, 'warn');
      return { lines: [`The wallet could not simulate this transaction, it may fail: ${errorMessage(err).slice(0, 120)}`], callsToo: true };
    }
  }

  /** Who pays a transaction: the network's sponsor when its fee call is there or the wallet adds it, else the payer it names. */
  async #payer(account: string, args: unknown[]): Promise<Payer> {
    const payload = obj(args[0]);
    // readCall caches its promise and permit() already awaited it: a call that fails here was refused before.
    const reads = await Promise.all(list(payload.calls).map(c => readCall(c, a => this.wallet.artifactAt(a)).catch(() => undefined)));
    if (reads.some(r => r?.fee)) return 'sponsor';
    const named = payload.feePayer === undefined ? undefined : String(payload.feePayer);
    if (named && named.toLowerCase() !== account.toLowerCase()) return named;
    // No payer named and no fee settings: the wallet pays through the sponsor where there is one (wallet.ts).
    return !named && obj(args[1]).fee === undefined && this.wallet.fees === 'sponsored' ? 'sponsor' : 'account';
  }

  /** Every signing op in full, numbered when a batch holds several, then the request as sent. */
  async #sections(ops: Op[]): Promise<Section[]> {
    const artifactOf = (address: string) => this.wallet.artifactAt(address);
    const parts = await Promise.all(
      ops.map((op, i) => {
        const prefix = ops.length > 1 ? `${i + 1}. ` : '';
        return op.name === 'sendTx' ? txSections(op.args, artifactOf, prefix) : authwitSections(op.args, artifactOf, prefix);
      }),
    );
    return [...parts.flat(), rawSection(ops.map(op => ({ method: op.name, args: op.args })))];
  }

  /** An op's lines in the approval: a transaction's summary (and its calls when needed), an authorization's call. */
  async #detailsOf(account: string, op: Op): Promise<string[]> {
    if (op.name !== 'sendTx') return this.#describe(op);
    const { lines, callsToo } = await this.#summary(account, op);
    return callsToo ? [...lines, ...(await this.#describe(op))] : lines;
  }

  #describe(op: Op): Promise<string[]> {
    const artifactOf = (address: string) => this.wallet.artifactAt(address);
    return op.name === 'sendTx' ? describeTx(op.args[0], artifactOf) : describeAuthWit(op.args[1], artifactOf);
  }

  async #run(session: ActiveSession, account: string, op: Op): Promise<unknown> {
    if (op.name === 'getAccounts') return [{ alias: 'main', item: account }];
    if (op.name === 'registerContract') await this.#noteRegistration(op.args);
    // The wallet's contacts are not the site's business.
    if (op.name === 'getAddressBook') return [];
    if (op.name !== 'sendTx') return rawCall(this.wallet, op.name, op.args);
    return this.#send(session, account, op.args);
  }

  /** A contract a site registers that the wallet did not know: its names are the site's, and shown as such. */
  async #noteRegistration(args: unknown[]): Promise<void> {
    const [instance] = (await parseArgs('registerContract', args)) as [ContractInstancePreimage];
    const address = (await computeContractAddressFromInstance(instance)).toString();
    // Unreadable counts as unknown: the contract is then marked as named by the site, the safe side.
    if (!(await this.wallet.artifactAt(address).catch(() => undefined))) markSiteRegistered(address);
  }

  /**
   * An approved transaction, reported to the app as it goes (`dappTx` events) so it lands in the wallet's
   * activity like the wallet's own: proving, then sent with its hash (the app follows it to a block) or failed.
   */
  async #send(session: ActiveSession, account: string, args: unknown[]): Promise<unknown> {
    const described = await describeTx(args[0], a => this.wallet.artifactAt(a)).catch(err => {
      log('dapp', `describing an approved transaction: ${errorMessage(err)}`, 'warn');
      return [];
    });
    const summary = described[0] ?? 'sendTx';
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

  /**
   * A site asks for capabilities. The first request, or one made after the user selected another account, asks the
   * user; a later one asks only when it adds something, listing what it adds, and is granted at once otherwise.
   */
  async #capabilities(session: ActiveSession, manifest: unknown): Promise<unknown> {
    const m = obj(manifest);
    const asked = list(m.capabilities).map(obj);
    const account = this.#selected;
    const before = this.#accounts.get(session.sessionId) === account ? this.#granted.get(session.sessionId) : undefined;
    const nameOf = (address: string) => this.wallet.artifactAt(address).then(a => a?.name);
    const more = before && added(before, grantsOf(asked));
    if (!more || more.flags.length || more.scopes.length) {
      const claim = obj(m.metadata).name ? [`It calls itself "${String(obj(m.metadata).name)}"`] : [];
      const ok = await this.#ask({
        kind: 'capabilities',
        tab: session.tabId,
        origin: session.origin,
        title: more ? 'Let this site do more?' : 'Let this site use your account?',
        details: more ? [...(await addedLines(more, nameOf)), ...claim] : [...asked.map(c => String(c.type)), ...claim],
        sections: [...(await capabilitySections(manifest, account, nameOf)), rawSection(manifest)],
      });
      if (!ok) throw new Error('the user refused the requested capabilities');
    }
    this.#granted.set(session.sessionId, merge(before ?? grantsOf([]), grantsOf(asked)));
    this.#accounts.set(session.sessionId, account);
    this.#publishSessions();
    const caps = asked.map(c => (c.type === 'accounts' ? { ...c, accounts: [{ alias: 'main', item: account }] } : c));
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
