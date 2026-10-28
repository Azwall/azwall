// Opens the PXE for one network: node client, compatibility check, encrypted stores, native prover.
import type { AztecNode } from '@aztec/aztec.js/node';
import { createLogger } from '@aztec/foundation/log';
import { deletePoolDirectory, storePoolDirectory } from '@aztec/kv-store/sqlite-opfs';
import { getVKTreeRoot } from '@aztec/noir-protocol-circuits-types/vk-tree';
import { protocolContractsHash } from '@aztec/protocol-contracts';
import { WASMSimulator } from '@aztec/simulator/client';
import { EmbeddedWalletEncryptionError, openEncryptedEmbeddedStores } from '@aztec/wallets/embedded/store-encryption';
import { type Bridge, errorMessage } from '@azwall/pxe-shared';

import { installBbMemo } from './bb_memo';
import { trustFeePayers } from './dapp/calls';
import { keepMarksIn, type MarkStore } from './dapp/provenance';
import { type L1Params, l1Client, pinnedPortal } from './fee_juice';
import { type FeeMode, feeModeOf } from './fees';
import { generationName, nextGeneration } from './generation';
import { log } from './log';
import { AppCopies, appKeepsStores, openAppStores } from './native_store';
import { recordNoteOwners } from './note_owners';
import { NativeKernelProver } from './prover';
import { CachedStore, clearBroken, forgetRows, isMarkedBroken } from './store';
import { AzwallWallet, sponsoredFpc } from './wallet';
import { nodeClient } from './version';

/** What the app passes to `open`: a network of networks.json with its resolved RPC URL. */
export type NetworkParams = {
  id: string;
  rpcUrl: string;
  rpcMaxBatch: number;
  aztec: string;
  fees: FeeMode;
  expected: { l1ChainId: number; rollupVersion: number; vkTreeRoot?: string; protocolContractsHash?: string };
  /** The network's Ethereum side, where bridged Fee Juice is found (fee_juice.ts). */
  l1?: L1Params;
};

export type NetworkCheck = {
  nodeVersion: string;
  l1ChainId: number;
  rollupVersion: number;
  vkTreeRoot: string;
  protocolContractsHash: string;
  blockNumber: number;
};

/** Refuses a node or client whose circuits or protocol contracts differ from the registry's pins. */
export async function checkNetwork(node: AztecNode, network: NetworkParams): Promise<NetworkCheck> {
  const info = await node.getNodeInfo();
  const check: NetworkCheck = {
    nodeVersion: info.nodeVersion,
    l1ChainId: info.l1ChainId,
    rollupVersion: Number(info.rollupVersion),
    vkTreeRoot: getVKTreeRoot().toString(),
    protocolContractsHash: protocolContractsHash.toString(),
    blockNumber: Number(await node.getBlockNumber()),
  };
  const problems = compatibilityProblems(check, network);
  if (problems.length) throw new Error(`network ${network.id} is not compatible:\n- ${problems.join('\n- ')}`);
  return check;
}

export function compatibilityProblems(check: Omit<NetworkCheck, 'blockNumber'>, network: NetworkParams): string[] {
  const e = network.expected;
  const problems: string[] = [];
  if (check.l1ChainId !== e.l1ChainId) problems.push(`L1 chain id ${check.l1ChainId} (expected ${e.l1ChainId})`);
  if (check.rollupVersion !== e.rollupVersion) problems.push(`rollup version ${check.rollupVersion} (expected ${e.rollupVersion})`);
  if (check.nodeVersion.split('.')[0] !== network.aztec.split('.')[0]) problems.push(`node ${check.nodeVersion}, client ${network.aztec}`);
  if (e.vkTreeRoot && check.vkTreeRoot !== e.vkTreeRoot) problems.push(`client VK tree root ${check.vkTreeRoot}`);
  if (e.protocolContractsHash && check.protocolContractsHash !== e.protocolContractsHash) {
    problems.push(`client protocol contracts hash ${check.protocolContractsHash}`);
  }
  return problems;
}

type RawStore = {
  allAsync(sql: string): Promise<unknown[][]>;
  runAsync(sql: string, bind?: unknown[]): Promise<unknown>;
  transactionAsync<T>(cb: () => Promise<T>): Promise<T>;
  close(): Promise<void>;
};

/** A store whose files cannot be used with this wallet's key (see unusableStore). */
export class UnusableStoreError extends Error {}

const PROBE = '__azwall_probe__';

/**
 * Opens the stores and checks each one can really be used: every page read back (`quick_check`), then a
 * transaction written like any flush. Files another key encrypted, or left half-written, can open and read fine
 * and still fail every write ("file is not a database"). A store marked broken by a failed flush (store.ts) is
 * refused too. On failure both stores are closed before the error goes on.
 */
async function openWritable<T extends { pxeStore: unknown; walletStore: unknown }>(open: () => Promise<T>, names: string[]): Promise<T> {
  const broken = names.find(isMarkedBroken);
  if (broken) throw new UnusableStoreError(`${broken} was marked broken by a failed write`);
  const stores = await open();
  const raw = [stores.pxeStore, stores.walletStore] as RawStore[];
  try {
    for (const store of raw) await checkWritable(store);
  } catch (err) {
    await Promise.allSettled(raw.map(store => store.close()));
    throw err;
  }
  return stores;
}

async function checkWritable(store: RawStore): Promise<void> {
  const check = String((await store.allAsync('PRAGMA quick_check'))[0]?.[0] ?? '');
  if (check !== 'ok') throw new UnusableStoreError(`integrity check: ${check}`);
  await store.transactionAsync(async () => {
    await store.runAsync('INSERT OR REPLACE INTO data (slot, container, key, key_count, hash, value) VALUES (?, ?, ?, ?, ?, ?)', [
      PROBE, PROBE, new Uint8Array([0]), 0, PROBE, null,
    ]);
    await store.runAsync('DELETE FROM data WHERE slot = ?', [PROBE]);
  });
}

/** True for stores this wallet's key cannot use: encrypted with another key, or not a database at all. */
export function unusableStore(err: unknown): boolean {
  if (err instanceof EmbeddedWalletEncryptionError || err instanceof UnusableStoreError) return true;
  return /SQLITE_NOTADB|SQLITE_CORRUPT|file is not a database|malformed/.test(err instanceof Error ? err.message : String(err));
}

/**
 * Opens the encrypted stores. Stores that the vault's key cannot decrypt were left by another wallet (the user
 * removed theirs and created or restored one: a new key): unreadable for good, they are discarded and the PXE
 * starts over, syncing from the chain again.
 */
export async function startOverOnOtherKey<T>(open: () => Promise<T>, discard: () => Promise<void>): Promise<T> {
  try {
    return await open();
  } catch (err) {
    if (!unusableStore(err)) throw err;
    log('open', `the stores cannot be used with this wallet's key (${errorMessage(err)}): starting over with empty stores`, 'warn');
    await discard();
    log('open', 'opening empty stores under new names');
    return open();
  }
}

/**
 * Opens the PXE and wallet stores under their current names (generation.ts), each in its own OPFS pool (the default
 * pool can only be held by one store at a time). Unusable stores are left behind for new names, and deleted in the
 * background.
 */
async function openOpfsStores(bases: [string, string], dbKey: Uint8Array) {
  const names = () => bases.map(generationName) as [string, string];
  const stores = await startOverOnOtherKey(
    () => {
      const [pxe, wallet] = names();
      const config = { pxe: { name: pxe, poolDirectory: storePoolDirectory(pxe) }, wallet: { name: wallet, poolDirectory: storePoolDirectory(wallet) } };
      // The buffer is transferred to the SQLite worker (and detached), so hand out a fresh copy.
      return openWritable(() => openEncryptedEmbeddedStores(config, async () => dbKey.slice(), createLogger('azwall:store')), [pxe, wallet]);
    },
    async () => {
      for (const base of bases) {
        const old = nextGeneration(base);
        forgetRows(old);
        clearBroken(old);
        deletePoolDirectory(storePoolDirectory(old)).then(
          () => log('open', `${old}: old store deleted`),
          err => log('open', `${old}: old store not deleted: ${errorMessage(err)}`, 'warn'),
        );
      }
    },
  );
  const [pxe, wallet] = names();
  const caches = [await CachedStore.wrap(stores.pxeStore as never, pxe), await CachedStore.wrap(stores.walletStore as never, wallet)];
  return { ...stores, caches };
}

/**
 * The chain id and rollup version transactions are signed for come from the wallet's own reading of the node, not
 * from checkNetwork's: both must be the registry's, or a node answering the check correctly and the wallet otherwise
 * would have it sign for another chain.
 */
export async function sameChainEverywhere(wallet: { getChainInfo(): Promise<{ chainId: { toBigInt(): bigint }; version: { toBigInt(): bigint } }> }, network: NetworkParams) {
  const chain = await wallet.getChainInfo();
  if (chain.chainId.toBigInt() !== BigInt(network.expected.l1ChainId) || chain.version.toBigInt() !== BigInt(network.expected.rollupVersion)) {
    throw new Error(`the node gave the wallet chain ${chain.chainId.toBigInt()} version ${chain.version.toBigInt()}, not this network's`);
  }
}

/** `dbKey`: 32-byte hex key of the encrypted stores, kept by the app's secure storage. */
export async function openWallet(bridge: Bridge, network: NetworkParams, dbKey: Uint8Array) {
  installBbMemo();
  const node = nodeClient(network.rpcUrl, network.rpcMaxBatch);
  const check = await checkNetwork(node, network);
  log('open', `node ${check.nodeVersion} is compatible; opening the encrypted stores`);
  const { l1ContractAddresses } = await node.getNodeInfo();
  const suffix = `${check.l1ChainId}-${l1ContractAddresses.rollupAddress.toString().slice(2, 10)}`;
  const names: [string, string] = [`pxe-${suffix}`, `wallet-${suffix}`];
  const { pxeStore, walletStore, caches: stores } = appKeepsStores()
    ? await openAppStores(names, new AppCopies(bridge, dbKey.slice()))
    : await openOpfsStores(names, dbKey);
  log('open', 'stores open; creating the PXE');
  // Before any simulation: approvals tell the account's own new notes from those it sends away.
  recordNoteOwners();
  const simulator = new WASMSimulator();
  // Creating the PXE loads Barretenberg's WebAssembly and its workers: say so if it takes long, so a slow start
  // and a stuck one can be told apart in the logs.
  const t0 = performance.now();
  const heartbeat = setInterval(() => log('open', `still creating the PXE (${Math.round((performance.now() - t0) / 1000)} s)`), 10_000);
  const wallet = await AzwallWallet.create<AzwallWallet>(node, {
    pxe: { store: pxeStore as never, simulator, proverOrOptions: new NativeKernelProver(simulator, bridge, network.id) },
    walletDb: { store: walletStore as never },
  }).finally(() => clearInterval(heartbeat));
  log('open', `PXE created in ${Math.round(performance.now() - t0)} ms`);
  wallet.stores = stores;
  // The contracts sites registered stay marked as named by them (dapp/provenance.ts).
  await keepMarksIn((walletStore as unknown as { openMap(name: string): MarkStore }).openMap('azwall-site-contracts'));
  const fpc = await sponsoredFpc();
  wallet.fees = await feeModeOf(network.fees, node, fpc.instance.address);
  if (wallet.fees === 'sponsored') await wallet.registerContract(fpc.instance, fpc.artifact);
  else log('wallet', 'no Sponsored FPC on this network: fees are paid with the account\'s Fee Juice');
  trustFeePayers(wallet.fees === 'sponsored' ? [fpc.instance.address.toString()] : []);
  await sameChainEverywhere(wallet, network);
  wallet.submitting = (from, txHash) => bridge.emit('txSubmitting', { from: from ?? null, txHash });
  if (network.l1) {
    const portal = pinnedPortal(l1ContractAddresses.feeJuicePortalAddress.toString(), network.l1);
    wallet.bridge = {
      client: l1Client(network.l1),
      portal,
      fromBlock: BigInt(network.l1.portalFromBlock),
      l1ChainId: network.expected.l1ChainId,
      rollupVersion: network.expected.rollupVersion,
    };
    log('wallet', `bridged Fee Juice is read on Ethereum (${network.l1.rpcUrls.map(u => new URL(u).host).join(', then ')}) from block ${network.l1.portalFromBlock}`);
  } else {
    log('wallet', 'no Ethereum side declared: bridged Fee Juice is not looked for');
  }
  log('wallet', `PXE ready on ${network.id}: node ${check.nodeVersion}, block ${check.blockNumber}`);
  return { wallet, check: { ...check, fees: wallet.fees } };
}
