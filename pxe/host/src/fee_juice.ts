// Fee Juice bridged from Ethereum. AZTEC deposited on the network's Fee Juice portal becomes Fee Juice once the
// deposit's L1 → L2 message is claimed on Aztec, with the secret whose hash the deposit carries. The message is bound
// to its recipient and amount: knowing the secret only lets someone credit the recipient. So deposits can use a public
// secret: 0 by default (its hash is a constant anyone can paste on Etherscan), or the one derived from the recipient
// (claimSecret). The wallet finds its deposits on Ethereum (the recipient is an indexed field of the portal's event)
// and claims those whose secret it knows: these two, and any the user entered for a deposit made otherwise.
import { DomainSeparator } from '@aztec/constants';
import { poseidon2Hash, poseidon2HashWithSeparator } from '@aztec/foundation/crypto/poseidon';
import { Fr } from '@aztec/foundation/curves/bn254';
import type { AztecNode } from '@aztec/stdlib/interfaces/client';
import { computeSecretHash, siloNullifier } from '@aztec/stdlib/hash';
import { ProtocolContractAddress } from '@aztec/protocol-contracts';
import type { AztecAddress } from '@aztec/stdlib/aztec-address';
import { MerkleTreeId } from '@aztec/stdlib/trees';
import { FeeJuicePaymentMethodWithClaim } from '@aztec/aztec.js/fee';
import { EthAddress } from '@aztec/foundation/eth-address';
import { AztecAddress as Address } from '@aztec/stdlib/aztec-address';
import { L1Actor, L1ToL2Message, L2Actor } from '@aztec/stdlib/messaging';
import { createPublicClient, encodeFunctionData, fallback, http, parseAbiItem, sha256, type PublicClient } from 'viem';

import { log } from './log';

/** The network's Ethereum side (networks.json `l1`, resolved by azwall-config): endpoints in the order they are tried. */
export type L1Params = { rpcUrls: string[]; portal: string; portalFromBlock: number };

/**
 * The portal deposits are read from: the registry's, pinned. A node naming another one is on another network, or
 * lies: its deposits would be someone else's (or nobody's), so the wallet refuses it.
 */
export function pinnedPortal(fromNode: string, l1: L1Params): `0x${string}` {
  if (fromNode.toLowerCase() !== l1.portal.toLowerCase()) {
    throw new Error(`the node names Fee Juice portal ${fromNode}, not this network's ${l1.portal}`);
  }
  return l1.portal as `0x${string}`;
}

/** A deposit for an account, as the portal's event tells it and the Aztec node sees its message. */
export type Deposit = {
  amount: bigint;
  messageHash: string;
  leafIndex: bigint;
  l1Tx: string;
  l1Block: bigint;
  /** The wallet knows its secret (a public one, or one the user entered): it can claim it. */
  ours: boolean;
  /** That secret, hex: claiming needs it. */
  secret?: string;
  /** Its message is in the Aztec chain: it can be claimed now. */
  ready: boolean;
  claimed: boolean;
};

/** The ASCII of `fee-juice-claim/v1`, as a field: the domain of the derived claim secret. */
export const CLAIM_SECRET_DOMAIN = new Fr(BigInt(`0x${Buffer.from('fee-juice-claim/v1').toString('hex')}`));

/** The claim secret of deposits to `recipient`: poseidon2([CLAIM_SECRET_DOMAIN, recipient]). Public by design. */
export function claimSecret(recipient: AztecAddress): Promise<Fr> {
  return poseidon2Hash([CLAIM_SECRET_DOMAIN, recipient.toField()]);
}

export const DEPOSIT_EVENT = parseAbiItem(
  'event DepositToAztecPublic(bytes32 indexed to, uint256 amount, bytes32 secretHash, bytes32 key, uint256 index)',
);

/** Public nodes cap the blocks one log query may span: 10,000 is what the fallback endpoints accept. */
const LOG_SPAN = 10_000n;

/** Blocks read again on each scan: a reorganisation of Ethereum's latest blocks can drop or move a deposit. */
const REORG_MARGIN = 64n;

/**
 * The most log queries one scan makes (a million blocks, about four months): the next scan goes on from there. An
 * endpoint that lies about its latest block cannot keep the wallet querying for ever.
 */
const MAX_SPANS = 100n;

/** The secrets the wallet tries for deposits to `recipient`: 0 (the default), the derived one, then the user's. */
export async function knownSecrets(recipient: AztecAddress, entered: Fr[] = []): Promise<Fr[]> {
  return [Fr.ZERO, await claimSecret(recipient), ...entered];
}

/** The portal's deposits to `to` in blocks `from` to `end`: one log query. */
function depositLogs(client: Pick<PublicClient, 'getLogs'>, portal: `0x${string}`, to: `0x${string}`, from: bigint, end: bigint) {
  return client.getLogs({ address: portal, event: DEPOSIT_EVENT, args: { to }, fromBlock: from, toBlock: end });
}

type DepositLog = Awaited<ReturnType<typeof depositLogs>>[number];

/**
 * What a scan of the portal found for a recipient, and up to which block: the next scan reads only what follows. Per
 * client (one per opened network), so another network or a test never sees these.
 */
const scans = new WeakMap<object, Map<string, { to: bigint; logs: DepositLog[] }>>();

/**
 * Every deposit the portal received for `recipient` from `fromBlock` on, oldest first. The blocks read before (but
 * the last REORG_MARGIN) are not read again: a refresh every 20 s would otherwise read the portal's whole history.
 */
export async function depositsTo(client: Pick<PublicClient, 'getLogs' | 'getBlockNumber'>, portal: `0x${string}`, recipient: AztecAddress, fromBlock: bigint) {
  const tip = await client.getBlockNumber();
  const to = recipient.toString() as `0x${string}`;
  const key = `${portal.toLowerCase()}|${to.toLowerCase()}|${fromBlock}`;
  const mine = scans.get(client) ?? new Map<string, { to: bigint; logs: DepositLog[] }>();
  scans.set(client, mine);
  const known = mine.get(key);
  const resume = known && known.to - REORG_MARGIN > fromBlock ? known.to - REORG_MARGIN : fromBlock;
  const found = (known?.logs ?? []).filter(l => (l.blockNumber ?? 0n) < resume);
  const last = resume + MAX_SPANS * LOG_SPAN - 1n < tip ? resume + MAX_SPANS * LOG_SPAN - 1n : tip;
  for (let start = resume; start <= last; start += LOG_SPAN) {
    const end = start + LOG_SPAN - 1n > last ? last : start + LOG_SPAN - 1n;
    found.push(...(await depositLogs(client, portal, to, start, end)));
  }
  mine.set(key, { to: last, logs: found });
  return found;
}

/** The chain a deposit's message is bound to: Ethereum's chain id and the rollup's version. */
export type MessageChain = { l1ChainId: number; rollupVersion: number };

const CLAIM_ABI = [{ type: 'function', name: 'claim', inputs: [{ type: 'bytes32' }, { type: 'uint256' }], outputs: [], stateMutability: 'nonpayable' }] as const;

/**
 * The L1 → L2 message hash of a portal deposit, as the portal and the Inbox build it: sender the address 0x…03 the
 * Inbox puts for the Fee Juice portal (with the L1 chain id), recipient FeeJuice 0x…03 (with the rollup version),
 * content sha256ToField(claim(to, amount)), then the secret hash and the leaf index. The bridge site computes it the
 * same way (aztec-bridge, src/lib/deposit.ts), checked there against a recorded mainnet deposit.
 */
export function depositMessageHash(d: { to: `0x${string}`; amount: bigint; secretHash: string; index: bigint }, chain: MessageChain): string {
  const calldata = encodeFunctionData({ abi: CLAIM_ABI, functionName: 'claim', args: [d.to, d.amount] });
  // sha256ToField: sha256, its first 31 bytes, left-padded with a zero byte.
  const content = Fr.fromHexString(`0x00${sha256(calldata).slice(2, 64)}`);
  const message = new L1ToL2Message(
    new L1Actor(EthAddress.fromString(`0x${'3'.padStart(40, '0')}`), chain.l1ChainId),
    new L2Actor(Address.fromBigIntUnsafe(3n), chain.rollupVersion),
    content,
    Fr.fromHexString(d.secretHash),
    new Fr(d.index),
  );
  return message.hash().toString();
}

/**
 * Whether a deposit log's message hash is the one its fields make: an Ethereum endpoint that lies about the amount
 * (or anything else the claim is bound to) is caught here, rather than when the claim fails.
 */
function bindsItsFields(entry: { args: unknown }, recipient: AztecAddress, chain: MessageChain): boolean {
  const a = entry.args as { amount: bigint; secretHash: string; key: string; index: bigint };
  const fields = { to: recipient.toString() as `0x${string}`, amount: a.amount, secretHash: a.secretHash, index: a.index };
  const ok = depositMessageHash(fields, chain) === a.key.toLowerCase();
  if (!ok) log('feeJuice', `a deposit read from Ethereum does not match its message hash (${a.key.slice(0, 10)}…): left out`, 'warn');
  return ok;
}

/** The nullifier a claimed message leaves on chain: poseidon2([messageHash, secret]), siloed by the Fee Juice contract. */
export async function claimNullifier(messageHash: Fr, secret: Fr): Promise<Fr> {
  const inner = await poseidon2HashWithSeparator([messageHash, secret], DomainSeparator.MESSAGE_NULLIFIER);
  return siloNullifier(ProtocolContractAddress.FeeJuice, inner);
}

type NodeReads = Pick<AztecNode, 'getL1ToL2MessageIndex' | 'getBlockData' | 'findLeavesIndexes'>;

/** Whether a deposit's message is in the chain, and whether it was claimed (its nullifier exists). */
async function onAztec(node: NodeReads, messageHash: Fr, secret: Fr): Promise<{ ready: boolean; claimed: boolean }> {
  const index = await node.getL1ToL2MessageIndex(messageHash);
  const block = index === undefined ? undefined : await node.getBlockData('latest');
  const ready = block !== undefined && index! < BigInt(block.header.state.l1ToL2MessageTree.nextAvailableLeafIndex);
  if (!ready) return { ready, claimed: false };
  const [leaf] = await node.findLeavesIndexes('latest', MerkleTreeId.NULLIFIER_TREE, [await claimNullifier(messageHash, secret)]);
  return { ready, claimed: leaf !== undefined };
}

/** The deposits on Ethereum for `recipient`, each with what the Aztec node knows of it. */
export async function feeJuiceDeposits(
  l1: Pick<PublicClient, 'getLogs' | 'getBlockNumber'>,
  node: NodeReads,
  portal: `0x${string}`,
  recipient: AztecAddress,
  fromBlock: bigint,
  entered: Fr[] = [],
  chain?: MessageChain,
): Promise<Deposit[]> {
  const secrets = await knownSecrets(recipient, entered);
  const byHash = new Map(await Promise.all(secrets.map(async s => [(await computeSecretHash(s)).toString().toLowerCase(), s] as const)));
  const logs = (await depositsTo(l1, portal, recipient, fromBlock)).filter(entry => !chain || bindsItsFields(entry, recipient, chain));
  return Promise.all(
    logs.map(async log => {
      const a = log.args as { amount: bigint; secretHash: string; key: string; index: bigint };
      const secret = byHash.get(a.secretHash.toLowerCase());
      const state = secret ? await onAztec(node, Fr.fromHexString(a.key), secret) : { ready: false, claimed: false };
      const base = { amount: a.amount, messageHash: a.key, leafIndex: a.index, l1Tx: log.transactionHash ?? '', l1Block: log.blockNumber ?? 0n };
      return { ...base, ours: secret !== undefined, secret: secret?.toString(), ...state };
    }),
  );
}

/** An Ethereum client for the network's endpoints: the next one answers when one fails or refuses a request. */
export function l1Client(params: L1Params): PublicClient {
  return createPublicClient({ transport: fallback(params.rpcUrls.map(url => http(url, { timeout: 15_000 }))) }) as PublicClient;
}

/** Where a network's deposits are read: its Ethereum endpoint, its Fee Juice portal and the portal's first block. */
export type FeeJuiceBridge = { client: PublicClient; portal: `0x${string}`; fromBlock: bigint; l1ChainId: number; rollupVersion: number };

/** The deposits for `recipient`, after checking the Ethereum endpoint is on the network's chain. */
export async function depositsFor(bridge: FeeJuiceBridge, node: NodeReads, recipient: AztecAddress, entered: Fr[] = []): Promise<Deposit[]> {
  const endpoint = await bridge.client.getChainId();
  if (endpoint !== bridge.l1ChainId) throw new Error(`the Ethereum endpoint is on chain ${endpoint}, the network on ${bridge.l1ChainId}`);
  const chain = { l1ChainId: bridge.l1ChainId, rollupVersion: bridge.rollupVersion };
  return feeJuiceDeposits(bridge.client, node, bridge.portal, recipient, bridge.fromBlock, entered, chain);
}

/** Pays a transaction's fee with Fee Juice claimed in it: the deposit `leafIndex` of `amount` to `owner`. */
export function claimPayment(owner: AztecAddress, amount: bigint, leafIndex: bigint, secret: Fr): FeeJuicePaymentMethodWithClaim {
  return new FeeJuicePaymentMethodWithClaim(owner, { claimAmount: amount, claimSecret: secret, messageLeafIndex: leafIndex });
}

/** A deposit as native code reads it: amounts and indexes as decimal strings. */
export function depositJson(d: Deposit) {
  return { ...d, amount: d.amount.toString(), leafIndex: d.leafIndex.toString(), l1Block: d.l1Block.toString() };
}
