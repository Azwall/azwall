// The methods native code calls on this PXE host. Parameters and results are plain JSON; amounts are
// decimal strings of base units, addresses and keys are 0x-prefixed hex.
import { NO_FROM } from '@aztec/aztec.js/account';
import { NO_WAIT } from '@aztec/aztec.js/contracts';
import { SponsoredFeePaymentMethod } from '@aztec/aztec.js/fee';
import { getFeeJuiceBalance } from '@aztec/aztec.js/utils';
import { Fq } from '@aztec/aztec.js/fields';
import { type Bridge, type Handlers, type Params, amount, asParams, bool, hex, int, object, str, strings } from '@azwall/pxe-shared';

import { DappGateway } from './dapp/gateway';
import { discoverTokens } from './discover';
import { tokenHistory } from './history';
import { openWallet, type NetworkParams } from './open';
import { addr, balances, registerTokens, deployToken, mint, tokenInfo, transfer, type TransferKind } from './token';
import { type AccountSecrets, type AzwallWallet, sponsoredFpc } from './wallet';

const TRANSFER_KINDS: TransferKind[] = ['private', 'public', 'shield', 'unshield'];

function networkParams(p: Params): NetworkParams {
  const e = object(p, 'expected');
  return {
    id: str(p, 'id'),
    rpcUrl: str(p, 'rpcUrl'),
    rpcMaxBatch: int(p, 'rpcMaxBatch'),
    aztec: str(p, 'aztec'),
    expected: {
      l1ChainId: int(e, 'l1ChainId'),
      rollupVersion: int(e, 'rollupVersion'),
      vkTreeRoot: typeof e.vkTreeRoot === 'string' ? e.vkTreeRoot : undefined,
      protocolContractsHash: typeof e.protocolContractsHash === 'string' ? e.protocolContractsHash : undefined,
    },
  };
}

function secrets(p: Params): AccountSecrets {
  return { alias: str(p, 'alias'), signingKey: hex(p, 'signingKey', 32), salt: hex(p, 'salt', 32) };
}

function fail(message: string): never {
  throw new Error(message);
}

function hexToBytes(h: string): Uint8Array {
  return Uint8Array.from(h.slice(2).match(/../g) ?? [], b => parseInt(b, 16));
}

export function createHandlers(bridge: Bridge): Handlers {
  let wallet: AzwallWallet | null = null;
  let dapps: DappGateway | null = null;
  // Opening can take minutes when damaged stores were set aside and the PXE syncs again from the chain.
  let opening = false;
  const w = () => wallet ?? fail(opening ? 'the wallet is still loading; try again in a moment' : 'the PXE is not open');
  const handlers: Record<string, (p: Params) => Promise<unknown>> = {
    open: async p => {
      if (wallet || opening) throw new Error('the PXE is already open');
      const [network, dbKey] = [networkParams(object(p, 'network')), hexToBytes(hex(p, 'dbKey', 32))];
      opening = true;
      const opened = await openWallet(bridge, network, dbKey).finally(() => (opening = false));
      wallet = opened.wallet;
      dapps = new DappGateway(bridge, opened.wallet, network);
      return opened.check;
    },
    close: async () => {
      await wallet?.shutdown();
      wallet = null;
    },
    newSigningKey: async () => ({ signingKey: Fq.random().toString() }),
    addAccount: async p => ({ address: (await w().addAccount(secrets(p))).address.toString() }),
    accountState: async p => ({ deployed: await w().isDeployed(addr(str(p, 'address'))) }),
    deployAccount: async p => deployAccount(w(), secrets(p)),
    blockNumber: async () => Number(await w().node.getBlockNumber()),
    waitForTx: async p => w().waitForReceipt(str(p, 'txHash')),
    txReceipt: async p => w().txReceipt(str(p, 'txHash')),
    tokenInfo: async p => tokenInfo(w(), str(p, 'token'), str(p, 'from')),
    balances: async p => balances(w(), str(p, 'owner'), strings(p, 'tokens')),
    transfer: async p => {
      const kind = str(p, 'kind') as TransferKind;
      if (!TRANSFER_KINDS.includes(kind)) throw new Error(`kind must be one of ${TRANSFER_KINDS.join(', ')}`);
      const txHash = await transfer(w(), kind, str(p, 'from'), str(p, 'to'), str(p, 'token'), amount(p, 'amount'));
      return { txHash };
    },
    deployToken: async p => deployToken(w(), str(p, 'admin'), str(p, 'name'), str(p, 'symbol'), int(p, 'decimals')),
    mint: async p => ({ txHash: await mint(w(), str(p, 'admin'), str(p, 'token'), str(p, 'to'), amount(p, 'amount'), bool(p, 'private')) }),
    registerTokens: async p => registerTokens(w(), strings(p, 'tokens')),
    tokenHistory: async p => tokenHistory(w(), str(p, 'owner'), strings(p, 'tokens')),
    syncStatus: async () => w().syncStatus(),
    discoverTokens: async p => discoverTokens(w(), str(p, 'owner'), strings(p, 'known')),
    flush: async () => w().flushStores(),
    ...dappHandlers(() => dapps),
    ...feeHandlers(w),
  };
  return Object.fromEntries(Object.entries(handlers).map(([k, f]) => [k, async (params: unknown) => f(asParams(params))]));
}

/** Fees where there is no sponsor (fees.ts). */
function feeHandlers(w: () => AzwallWallet): Record<string, (p: Params) => Promise<unknown>> {
  return {
    // Base units (18 decimals) of the Fee Juice an address holds: what pays its fees.
    feeJuiceBalance: async p => ({ balance: (await getFeeJuiceBalance(addr(str(p, 'owner')), w().node)).toString() }),
  };
}

/** Messages of the browser's pages, for the dApp gateway (none before the PXE is open). */
function dappHandlers(dapps: () => DappGateway | null): Record<string, (p: Params) => Promise<unknown>> {
  return {
    // A message from the content script of the page at `origin` (taken from the native web view, not the page).
    dappMessage: async p => dapps()?.receive(int(p, 'tabId'), str(p, 'origin'), JSON.parse(str(p, 'message')), str(p, 'account')),
    dappDisconnect: async p => dapps()?.disconnect(str(p, 'sessionId')),
    dappClosePage: async p => dapps()?.closePage(int(p, 'tabId')),
  };
}

/**
 * Deploys the account contract. The Sponsored FPC pays where there is one; elsewhere the account pays for its own
 * deployment with the Fee Juice already bridged to its address (no payment method: self-paid).
 */
async function deployAccount(wallet: AzwallWallet, acc: AccountSecrets) {
  const manager = await wallet.addAccount(acc);
  const deploy = await manager.getDeployMethod();
  const sponsor = wallet.fees === 'sponsored' ? new SponsoredFeePaymentMethod((await sponsoredFpc()).instance.address) : undefined;
  const { txHash } = await deploy.send({ from: NO_FROM, fee: sponsor && { paymentMethod: sponsor }, wait: NO_WAIT });
  return { txHash: txHash.toString() };
}
