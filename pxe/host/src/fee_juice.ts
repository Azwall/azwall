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
import { createPublicClient, http, parseAbiItem, type PublicClient } from 'viem';

/** The network's Ethereum side (networks.json `l1`, resolved by azwall-config). */
export type L1Params = { rpcUrl: string; portalFromBlock: number };

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

/** Public nodes cap the blocks one log query may span. */
const LOG_SPAN = 50_000n;

/** The secrets the wallet tries for deposits to `recipient`: 0 (the default), the derived one, then the user's. */
export async function knownSecrets(recipient: AztecAddress, entered: Fr[] = []): Promise<Fr[]> {
  return [Fr.ZERO, await claimSecret(recipient), ...entered];
}

/** Every deposit the portal received for `recipient` from `fromBlock` on, oldest first. */
export async function depositsTo(client: Pick<PublicClient, 'getLogs' | 'getBlockNumber'>, portal: `0x${string}`, recipient: AztecAddress, fromBlock: bigint) {
  const tip = await client.getBlockNumber();
  const to = recipient.toString() as `0x${string}`;
  const found = [];
  for (let start = fromBlock; start <= tip; start += LOG_SPAN) {
    const end = start + LOG_SPAN - 1n > tip ? tip : start + LOG_SPAN - 1n;
    found.push(...(await client.getLogs({ address: portal, event: DEPOSIT_EVENT, args: { to }, fromBlock: start, toBlock: end })));
  }
  return found;
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
): Promise<Deposit[]> {
  const secrets = await knownSecrets(recipient, entered);
  const byHash = new Map(await Promise.all(secrets.map(async s => [(await computeSecretHash(s)).toString().toLowerCase(), s] as const)));
  const logs = await depositsTo(l1, portal, recipient, fromBlock);
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

/** An Ethereum client for the network's endpoint. */
export function l1Client(params: L1Params): PublicClient {
  return createPublicClient({ transport: http(params.rpcUrl) }) as PublicClient;
}

/** Where a network's deposits are read: its Ethereum endpoint, its Fee Juice portal and the portal's first block. */
export type FeeJuiceBridge = { client: PublicClient; portal: `0x${string}`; fromBlock: bigint; l1ChainId: number };

/** The deposits for `recipient`, after checking the Ethereum endpoint is on the network's chain. */
export async function depositsFor(bridge: FeeJuiceBridge, node: NodeReads, recipient: AztecAddress, entered: Fr[] = []): Promise<Deposit[]> {
  const chain = await bridge.client.getChainId();
  if (chain !== bridge.l1ChainId) throw new Error(`the Ethereum endpoint is on chain ${chain}, the network on ${bridge.l1ChainId}`);
  return feeJuiceDeposits(bridge.client, node, bridge.portal, recipient, bridge.fromBlock, entered);
}

/** Pays a transaction's fee with Fee Juice claimed in it: the deposit `leafIndex` of `amount` to `owner`. */
export function claimPayment(owner: AztecAddress, amount: bigint, leafIndex: bigint, secret: Fr): FeeJuicePaymentMethodWithClaim {
  return new FeeJuicePaymentMethodWithClaim(owner, { claimAmount: amount, claimSecret: secret, messageLeafIndex: leafIndex });
}

/** A deposit as native code reads it: amounts and indexes as decimal strings. */
export function depositJson(d: Deposit) {
  return { ...d, amount: d.amount.toString(), leafIndex: d.leafIndex.toString(), l1Block: d.l1Block.toString() };
}
