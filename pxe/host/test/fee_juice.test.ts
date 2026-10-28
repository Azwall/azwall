import { Fr } from '@aztec/foundation/curves/bn254';
import { poseidon2Hash } from '@aztec/foundation/crypto/poseidon';
import { AztecAddress } from '@aztec/stdlib/aztec-address';
import { computeSecretHash } from '@aztec/stdlib/hash';
import { describe, expect, it } from 'vitest';

import { CLAIM_SECRET_DOMAIN, claimNullifier, claimSecret, depositsFor, depositsTo, feeJuiceDeposits } from '../src/fee_juice';

const me = AztecAddress.fromBigIntUnsafe(0x1d7cn);
const portal = '0x534361be805ca30bdb6404ec5e6cdaffb3e9f3b3';

type Log = { args: { amount: bigint; secretHash: string; key: string; index: bigint }; transactionHash: string; blockNumber: bigint };

/** An Ethereum node holding `logs`; records each log query's block range. */
function l1(logs: Log[], tip = 120_000n, chainId = 1) {
  const ranges: [bigint, bigint][] = [];
  const node = {
    ranges,
    tip,
    getChainId: async () => chainId,
    getBlockNumber: async () => node.tip,
    getLogs: async (q: { fromBlock: bigint; toBlock: bigint; args: { to: string } }) => {
      ranges.push([q.fromBlock, q.toBlock]);
      expect(q.args.to).toBe(me.toString());
      return logs.filter(l => l.blockNumber >= q.fromBlock && l.blockNumber <= q.toBlock);
    },
  };
  return node;
}

/** An Aztec node whose message tree holds `inTree` (message hash → leaf index) and whose nullifiers are `spent`. */
function aztec(inTree: Map<string, bigint>, spent: string[], nextLeaf = 100n) {
  return {
    getL1ToL2MessageIndex: async (h: Fr) => inTree.get(h.toString()),
    getBlockData: async () => ({ header: { state: { l1ToL2MessageTree: { nextAvailableLeafIndex: Number(nextLeaf) } } } }),
    findLeavesIndexes: async (_b: unknown, _t: unknown, values: Fr[]) => values.map(v => (spent.includes(v.toString()) ? { data: 1n } : undefined)),
  };
}

async function log(amount: bigint, index: bigint, block: bigint, secretHash?: string): Promise<Log> {
  const key = new Fr(1000n + index).toString();
  const hash = secretHash ?? (await computeSecretHash(await claimSecret(me))).toString();
  return { args: { amount, secretHash: hash, key, index }, transactionHash: `0x${'aa'.repeat(32)}`, blockNumber: block };
}

describe('bridged Fee Juice', () => {
  it('derives the claim secret from the recipient, as the bridge site does', async () => {
    expect(CLAIM_SECRET_DOMAIN.toBigInt()).toBe(BigInt(`0x${Buffer.from('fee-juice-claim/v1').toString('hex')}`));
    expect((await claimSecret(me)).equals(await poseidon2Hash([CLAIM_SECRET_DOMAIN, me.toField()]))).toBe(true);
    expect((await claimSecret(me)).equals(await claimSecret(AztecAddress.fromBigIntUnsafe(2n)))).toBe(false);
  });

  it('reads the portal in spans public nodes accept, from the portal\'s first block', async () => {
    const node = l1([await log(5n, 1n, 25_000n)], 45_000n);
    const logs = await depositsTo(node as never, portal, me, 10_000n);
    expect(logs).toHaveLength(1);
    expect(node.ranges).toEqual([[10_000n, 19_999n], [20_000n, 29_999n], [30_000n, 39_999n], [40_000n, 45_000n]]);
  });

  it('reads again only the blocks after the last scan, and the latest ones a reorganisation could change', async () => {
    const node = l1([await log(5n, 1n, 1_000n)], 30_000n);
    await depositsTo(node as never, portal, me, 0n);
    node.ranges.length = 0;
    node.tip = 30_500n;
    const logs = await depositsTo(node as never, portal, me, 0n);
    expect(node.ranges).toEqual([[29_936n, 30_500n]]);
    expect(logs).toHaveLength(1);
  });

  it('tells deposits it can claim, waiting, claimed, and others\' secrets apart', async () => {
    const secret = await claimSecret(me);
    const ready = await log(5n * 10n ** 18n, 1n, 50n);
    const claimed = await log(2n, 2n, 51n);
    const waiting = await log(3n, 3n, 52n);
    const foreign = await log(4n, 4n, 53n, new Fr(7n).toString());
    const inTree = new Map([[ready.args.key, 10n], [claimed.args.key, 11n], [waiting.args.key, 500n]]);
    const spent = [(await claimNullifier(Fr.fromHexString(claimed.args.key), secret)).toString()];
    const found = await feeJuiceDeposits(l1([ready, claimed, waiting, foreign]) as never, aztec(inTree, spent) as never, portal, me, 0n);
    const state = found.map(d => [d.amount, d.ours, d.ready, d.claimed]);
    expect(state).toEqual([
      [5n * 10n ** 18n, true, true, false],
      [2n, true, true, true],
      [3n, true, false, false],
      [4n, false, false, false],
    ]);
    expect(found[0].leafIndex).toBe(1n);
    expect(found[0].messageHash).toBe(ready.args.key);
  });

  it('claims deposits made with secret 0 (the default) or a secret the user entered, and says which secret', async () => {
    const zeroHash = (await computeSecretHash(Fr.ZERO)).toString();
    expect(zeroHash).toBe('0x1f8eff65d91ed781c2e7a28a2ff99b7f7506b7293121b5ffcf3cd339c84d2250');
    const entered = new Fr(424242n);
    const byZero = await log(1n, 1n, 10n, zeroHash);
    const byEntered = await log(2n, 2n, 11n, (await computeSecretHash(entered)).toString());
    const inTree = new Map([[byZero.args.key, 1n], [byEntered.args.key, 2n]]);
    const without = await feeJuiceDeposits(l1([byZero, byEntered]) as never, aztec(inTree, []) as never, portal, me, 0n);
    expect(without.map(d => d.ours)).toEqual([true, false]);
    expect(without[0].secret).toBe(Fr.ZERO.toString());
    const withIt = await feeJuiceDeposits(l1([byZero, byEntered]) as never, aztec(inTree, []) as never, portal, me, 0n, [entered]);
    expect(withIt.map(d => [d.ours, d.ready])).toEqual([[true, true], [true, true]]);
    expect(withIt[1].secret).toBe(entered.toString());
  });

  it('refuses an Ethereum endpoint on another chain', async () => {
    const bridge = { client: l1([], 1n, 11155111) as never, portal: portal as `0x${string}`, fromBlock: 0n, l1ChainId: 1 };
    await expect(depositsFor(bridge, aztec(new Map(), []) as never, me)).rejects.toThrow('chain 11155111');
  });
});
