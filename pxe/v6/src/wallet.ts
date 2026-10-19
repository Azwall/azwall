// The wallet: Aztec's EmbeddedWallet (PXE + stores), with account secrets kept in memory only (they come
// from the app's secure storage), fees paid by the Sponsored FPC, and the gas cache of gas.ts.
import { deriveSecretKeyFromSigningKey } from '@aztec-labs/accounts/utils';
import { NO_FROM } from '@aztec-labs/aztec.js/account';
import { AztecAddress } from '@aztec-labs/aztec.js/addresses';
import { getContractInstanceFromInstantiationParams, type InteractionWaitOptions, type SendReturn } from '@aztec-labs/aztec.js/contracts';
import { SponsoredFeePaymentMethod } from '@aztec-labs/aztec.js/fee';
import { Fq, Fr } from '@aztec-labs/aztec.js/fields';
import { type AztecNode, waitForTx } from '@aztec-labs/aztec.js/node';
import type { ExecutionPayload } from '@aztec-labs/aztec.js/tx';
import { AccountManager, type Aliased, ContractInitializationStatus, type SendOptions } from '@aztec-labs/aztec.js/wallet';
import { poseidon2Hash } from '@aztec-labs/foundation/crypto/poseidon';
import { SPONSORED_FPC_SALT } from '@aztec-labs/constants';
import { AccountFeePaymentMethodOptions } from '@aztec-labs/entrypoints/account';
import type { LogFn } from '@aztec-labs/foundation/log';
import type { AztecAsyncKVStore } from '@aztec-labs/kv-store';
import { SponsoredFPCContractArtifact } from '@aztec-labs/noir-contracts.js/SponsoredFPC';
import { TxHash } from '@aztec-labs/stdlib/tx';
import { BaseWallet, type CompleteFeeOptionsConfig, type FeeOptions } from '@aztec-labs/wallet-sdk/base-wallet';
import { Gas } from '@aztec-labs/stdlib/gas';
import { type AccountType, EmbeddedWallet, WalletDB } from '@aztec-labs/wallets/embedded';

import { looksLikeToken } from './discover';
import { GasCache } from './gas';
import { PqAccountContract } from './pq/account';
import { log } from './log';
import type { CachedStore } from './store';

/** Testnet blocks are slow and proving is client-side; give mining a generous budget. */
export const TX_TIMEOUT_S = 600;

/** Account material as the app's secure storage holds it (hex strings). */
export type AccountSecrets = { alias: string; signingKey: string; salt: string };

/** `outcome` folds the node's statuses into what the app shows: still waiting, in a block (ran or reverted), or gone. */
export type TxOutcome = 'pending' | 'success' | 'reverted' | 'dropped';
export type Receipt = { txHash: string; status: string; blockNumber: number | null; outcome: TxOutcome };

type InMemoryAccount = { type: AccountType; secretKey: Fr; salt: Fr; signingKey: Buffer; alias: string };

/** WalletDB whose account secrets never reach disk; other wallet data persists in the encrypted store. */
class InMemoryAccountsDB extends WalletDB {
  readonly #accounts = new Map<string, InMemoryAccount>();

  constructor(store: AztecAsyncKVStore, userLog: LogFn) {
    super(store, userLog);
  }

  override async storeAccount(
    address: AztecAddress,
    acc: { type: AccountType; secretKey: Fr; salt: Fr; signingKey: Fq | Buffer; alias: string | undefined },
  ) {
    const signingKey = Buffer.isBuffer(acc.signingKey) ? acc.signingKey : acc.signingKey.toBuffer();
    this.#accounts.set(address.toString(), { ...acc, signingKey, alias: acc.alias ?? '' });
  }

  override async retrieveAccount(address: AztecAddress | string) {
    const acc = this.#accounts.get(address.toString());
    if (!acc) throw new Error(`Account ${address} is not unlocked`);
    return { address, secretKey: acc.secretKey, salt: acc.salt, type: acc.type, signingKey: acc.signingKey };
  }

  override async listAccounts(): Promise<Aliased<AztecAddress>[]> {
    return [...this.#accounts.entries()].map(([address, acc]) => ({ alias: acc.alias, item: AztecAddress.fromStringUnsafe(address) }));
  }

  override async deleteAccount(address: AztecAddress) {
    this.#accounts.delete(address.toString());
  }
}

export async function sponsoredFpc() {
  const instance = await getContractInstanceFromInstantiationParams(SponsoredFPCContractArtifact, { salt: new Fr(SPONSORED_FPC_SALT) });
  return { instance, artifact: SponsoredFPCContractArtifact };
}

type NodeReceipt = { txHash: TxHash; status: unknown; blockNumber?: number | bigint | null; executionResult?: unknown };

export const outcomeOf = (r: Pick<NodeReceipt, 'status' | 'executionResult'>): TxOutcome => {
  const status = String(r.status);
  if (status === 'dropped') return 'dropped';
  if (status === 'pending') return 'pending';
  // Mined (proposed or later): the execution result says whether the app logic ran or reverted.
  return r.executionResult === undefined || String(r.executionResult) === 'success' ? 'success' : 'reverted';
};

export const toReceipt = (r: NodeReceipt): Receipt => ({
  txHash: r.txHash.toString(),
  status: String(r.status),
  blockNumber: r.blockNumber === undefined || r.blockNumber === null ? null : Number(r.blockNumber),
  outcome: outcomeOf(r),
});

export class AzwallWallet extends EmbeddedWallet {
  readonly gas = new GasCache();
  stores: CachedStore[] = [];

  constructor(...args: ConstructorParameters<typeof EmbeddedWallet>) {
    super(...args);
    // WalletDB keeps its store private; reuse it so non-secret wallet data keeps persisting (encrypted).
    const store = (args[2] as unknown as { store: AztecAsyncKVStore }).store;
    this.walletDB = new InMemoryAccountsDB(store, m => log('walletdb', m));
    this.gas.instrument(this);
  }

  get node(): AztecNode {
    return this.aztecNode;
  }

  override async sendTx<W extends InteractionWaitOptions = undefined>(payload: ExecutionPayload, opts: SendOptions<W>): Promise<SendReturn<W>> {
    if (opts.from !== NO_FROM && this.#pqAccounts.has(opts.from.toString())) return this.#sendFromPq(payload, opts);
    return this.gas.send(this, payload, opts, (p, o) => super.sendTx(p, o));
  }

  /**
   * EmbeddedWallet estimates gas by simulating the account through a stub chosen by account type, and has none for
   * the post-quantum account: prove directly instead, with the network's admission limits (sponsored on testnet).
   */
  async #sendFromPq<W extends InteractionWaitOptions>(payload: ExecutionPayload, opts: SendOptions<W>): Promise<SendReturn<W>> {
    await this.pxe.sync();
    const gasSettings = { ...opts.fee?.gasSettings, gasLimits: await this.getMaxTxGasLimits(), teardownGasLimits: Gas.empty() };
    return BaseWallet.prototype.sendTx.call(this, payload, { ...opts, fee: { ...opts.fee, gasSettings } }) as Promise<SendReturn<W>>;
  }

  /** Pays every fee through the Sponsored FPC unless the caller picks a fee payer. */
  override async completeFeeOptions(config: CompleteFeeOptionsConfig): Promise<FeeOptions> {
    const base = await super.completeFeeOptions(config);
    if (config.feePayer) return base;
    const fpc = await sponsoredFpc();
    return {
      ...base,
      walletFeePaymentMethod: new SponsoredFeePaymentMethod(fpc.instance.address),
      accountFeePaymentMethodOptions: config.from !== NO_FROM ? AccountFeePaymentMethodOptions.EXTERNAL : base.accountFeePaymentMethodOptions,
    };
  }

  /** Experimental post-quantum accounts (pq/account.ts), by address: not in the walletDB, which only knows built-in types. */
  readonly #pqAccounts = new Map<string, AccountManager>();

  override async getAccountFromAddress(address: AztecAddress) {
    const pq = this.#pqAccounts.get(address.toString());
    return pq ? pq.getAccount() : super.getAccountFromAddress(address);
  }

  /** Registers a post-quantum account whose one-time keys are `levels` (from `seed`); nothing is sent. */
  async addPqAccount(seed: Fr, levels: Fr[][]): Promise<AccountManager> {
    const secretKey = await poseidon2Hash([seed, new Fr(1)]);
    const contract = new PqAccountContract(seed, levels);
    const manager = await AccountManager.create(this, secretKey, contract, { salt: Fr.ZERO });
    await this.registerContract(manager.getInstance(), await contract.getContractArtifact(), secretKey);
    this.#pqAccounts.set(manager.address.toString(), manager);
    return manager;
  }

  /** Registers a Schnorr account from its secrets and returns its manager; nothing is sent. */
  async addAccount(acc: AccountSecrets) {
    const signingKey = Fq.fromHexString(acc.signingKey);
    const secretKey = await deriveSecretKeyFromSigningKey(signingKey);
    return this.createSchnorrAccount(secretKey, Fr.fromHexString(acc.salt), signingKey, acc.alias);
  }

  async isDeployed(address: AztecAddress): Promise<boolean> {
    const metadata = await this.getContractMetadata(address);
    return metadata.initializationStatus === ContractInitializationStatus.INITIALIZED;
  }

  async waitForReceipt(txHash: string): Promise<Receipt> {
    return toReceipt(await waitForTx(this.aztecNode, TxHash.fromString(txHash), { timeout: TX_TIMEOUT_S }));
  }

  /** The receipt as it stands, without waiting: to reconcile transactions whose wait was interrupted. */
  async txReceipt(txHash: string): Promise<Receipt> {
    return toReceipt(await this.aztecNode.getTxReceipt(TxHash.fromString(txHash)));
  }

  /** Where the PXE stands: its last synced block (number and time, in seconds) and the chain's tip. */
  async syncStatus(): Promise<{ syncedBlock: number; syncedAt: number; tip: number }> {
    const tip = Number(await this.aztecNode.getBlockNumber());
    // A PXE that never synced has no header yet: it starts from the beginning.
    const header = await this.pxe.getSyncedBlockHeader().catch(() => undefined);
    const g = header?.globalVariables;
    return { syncedBlock: g ? Number(g.blockNumber) : 0, syncedAt: g ? Number(g.timestamp) : 0, tip };
  }

  /** Contracts registered in this PXE (by the wallet or by dApps) whose artifact is a token's. */
  async registeredTokens(): Promise<string[]> {
    const addresses = await this.pxe.getContracts();
    const tokens = await Promise.all(
      addresses.map(async address => {
        const instance = await this.pxe.getContractInstance(address);
        const artifact = instance && (await this.pxe.getContractArtifact(instance.originalContractClassId));
        return looksLikeToken(artifact) ? address.toString() : null;
      }),
    );
    log('tokens', `${addresses.length} contracts registered in the PXE, ${tokens.filter(t => t).length} of them tokens`);
    return tokens.filter(t => t !== null);
  }

  async flushStores(): Promise<void> {
    await Promise.all(this.stores.map(s => s.flush()));
  }

  async shutdown(): Promise<void> {
    await this.flushStores();
    await this.stop();
  }
}
