// Opens the PXE for one network: node client, compatibility check, encrypted stores, native prover.
import { createAztecNodeClient, type AztecNode } from '@aztec/aztec.js/node';
import { createLogger } from '@aztec/foundation/log';
import { storePoolDirectory } from '@aztec/kv-store/sqlite-opfs';
import { getVKTreeRoot } from '@aztec/noir-protocol-circuits-types/vk-tree';
import { protocolContractsHash } from '@aztec/protocol-contracts';
import { WASMSimulator } from '@aztec/simulator/client';
import { openEncryptedEmbeddedStores } from '@aztec/wallets/embedded/store-encryption';
import type { Bridge } from '@azwall/pxe-shared';

import { installBbMemo } from './bb_memo';
import { log } from './log';
import { NativeKernelProver } from './prover';
import { CachedStore } from './store';
import { AzwallWallet, sponsoredFpc } from './wallet';

/** What the app passes to `open`: a network of networks.json with its resolved RPC URL. */
export type NetworkParams = {
  id: string;
  rpcUrl: string;
  rpcMaxBatch: number;
  aztec: string;
  expected: { l1ChainId: number; rollupVersion: number; vkTreeRoot?: string; protocolContractsHash?: string };
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

/** `dbKey`: 32-byte hex key of the encrypted stores, kept by the app's secure storage. */
export async function openWallet(bridge: Bridge, network: NetworkParams, dbKey: Uint8Array) {
  installBbMemo();
  // The 5.x client has no batch size limit option (rpcMaxBatch only applies to 6.x hosts).
  const node = createAztecNodeClient(network.rpcUrl);
  const check = await checkNetwork(node, network);
  log('open', `node ${check.nodeVersion} is compatible; opening the encrypted stores`);
  const { l1ContractAddresses } = await node.getNodeInfo();
  const suffix = `${check.l1ChainId}-${l1ContractAddresses.rollupAddress.toString().slice(2, 10)}`;
  const names = { pxe: `pxe-${suffix}`, wallet: `wallet-${suffix}` };
  const { pxeStore, walletStore } = await openEncryptedEmbeddedStores(
    // Each store needs its own OPFS pool; the default pool can only be held by one store at a time.
    {
      pxe: { name: names.pxe, poolDirectory: storePoolDirectory(names.pxe) },
      wallet: { name: names.wallet, poolDirectory: storePoolDirectory(names.wallet) },
    },
    // The buffer is transferred to the SQLite worker (and detached), so hand out a fresh copy.
    async () => dbKey.slice(),
    createLogger('azwall:store'),
  );
  const stores = [await CachedStore.wrap(pxeStore as never, 'pxe'), await CachedStore.wrap(walletStore as never, 'wallet')];
  log('open', 'stores open; creating the PXE');
  const simulator = new WASMSimulator();
  const wallet = await AzwallWallet.create<AzwallWallet>(node, {
    pxe: { store: pxeStore as never, simulator, proverOrOptions: new NativeKernelProver(simulator, bridge, network.id) },
    walletDb: { store: walletStore as never },
  });
  wallet.stores = stores;
  const fpc = await sponsoredFpc();
  await wallet.registerContract(fpc.instance, fpc.artifact);
  log('wallet', `PXE ready on ${network.id}: node ${check.nodeVersion}, block ${check.blockNumber}`);
  return { wallet, check };
}
