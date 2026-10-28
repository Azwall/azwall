// What a transaction does to the account's token balances, found by simulating it before the user approves it: the
// public balances it writes, the account's private notes it spends and the notes it creates for the account. Read
// from the transaction's own effects, never from what a site says, and shown first in its approval.
import { AztecAddress } from '@aztec/aztec.js/addresses';
import type { ExecutionPayload } from '@aztec/aztec.js/tx';
import type { GasFees } from '@aztec/stdlib/gas';
import { computePublicDataTreeLeafSlot, deriveStorageSlotInMap } from '@aztec/stdlib/hash';

import { log } from '../log';
import { noteOrigin } from '../note_owners';
import { tokenInfo } from '../token';
import type { AzwallWallet } from '../wallet';
import { oneLine } from './calls';
import { parseArgs } from './raw_call';

export type BalanceChange = { token: string; kind: 'public' | 'private'; delta: bigint };
/**
 * A transaction's effects on the account: its balance changes, about what its fee comes to, and the most the
 * priority fee the site set can add to it (0 without one), in Fee Juice units.
 */
export type Effects = { changes: BalanceChange[]; fee: bigint; tip: bigint };
/** Who pays a transaction's fee: the network's sponsor, the account, or another contract (its address). */
export type Payer = 'sponsor' | 'account' | string;
export type TokenMeta = { symbol: string; decimals: number };

/** One call of a simulated transaction, as far as its notes go. */
export type CallNotes = {
  newNotes: { note: { items: { toBigInt(): bigint }[] }; storageSlot: { toBigInt(): bigint } }[];
  noteHashNullifierCounterMap: Map<number, number>;
  nestedExecutionResults: CallNotes[];
};

/** A token's balance slots: its public balances map, and the slots its private balance notes sit at for `me`. */
type Slots = { publicMap?: bigint; privateSlots: Set<bigint> };

const flatten = (c: CallNotes): CallNotes[] => [c, ...c.nestedExecutionResults.flatMap(flatten)];

/**
 * The notes a transaction creates for `me` in each token, net of those it spends again within itself (a note created
 * and nullified in the same transaction never lands). Keyed by lower-case contract address.
 */
export function createdFor(me: string, root: CallNotes, slots: Map<string, Slots>): Map<string, bigint> {
  const calls = flatten(root);
  const squashed = new Set(calls.flatMap(c => [...c.noteHashNullifierCounterMap.keys()]));
  const out = new Map<string, bigint>();
  for (const n of calls.flatMap(c => c.newNotes)) {
    const origin = noteOrigin(n);
    const token = origin && slots.get(origin.contract);
    if (!origin || !token || origin.owner !== me.toLowerCase() || squashed.has(origin.counter)) continue;
    if (!token.privateSlots.has(n.storageSlot.toBigInt())) continue;
    out.set(origin.contract, (out.get(origin.contract) ?? 0n) + (n.note.items[0]?.toBigInt() ?? 0n));
  }
  return out;
}

/** `v` in a token's units, without trailing zeros, at most 8 decimals: 2475000000000000n with 18 → "0.002475". */
export function formatAmount(v: bigint, decimals: number): string {
  const abs = v < 0n ? -v : v;
  const unit = 10n ** BigInt(decimals);
  const fraction = (abs % unit).toString().padStart(decimals, '0').slice(0, 8).replace(/0+$/, '');
  return `${abs / unit}${fraction ? `.${fraction}` : ''}`;
}

/**
 * The approval's lines: what leaves the account first, then what comes in. A token outside `followed` (the ones the
 * user follows in the wallet) is marked: a fake "USDC" a site minted would otherwise make "You send 100 USDC / You
 * receive 100 USDC" read as a round trip.
 */
export function changeLines(changes: BalanceChange[], meta: Map<string, TokenMeta>, followed: Set<string>): string[] {
  const line = (c: BalanceChange) => {
    const known = meta.get(c.token);
    const m = known ? { ...known, symbol: oneLine(known.symbol, 16) } : { symbol: 'of an unknown token', decimals: 0 };
    // A symbol is what the token's deployer chose: its address tells two "USDC" apart.
    const mark = followed.has(c.token.toLowerCase()) ? '' : ', not a token you follow';
    return `${c.delta < 0n ? 'You send' : 'You receive'} ${formatAmount(c.delta, m.decimals)} ${m.symbol} (${c.kind}, ${c.token.slice(0, 8)}…${c.token.slice(-4)}${mark})`;
  };
  return [...changes.filter(c => c.delta < 0n), ...changes.filter(c => c.delta > 0n)].map(line);
}

async function slotsOf(wallet: AzwallWallet, token: string, me: AztecAddress): Promise<Slots | undefined> {
  const layout = (await wallet.artifactAt(token))?.storageLayout;
  if (!layout) return undefined;
  const base = layout.balances?.slot;
  // Private balance notes sit at the balances slot, or at the account's entry of a balances map.
  const privateSlots = new Set(base ? [base.toBigInt(), (await deriveStorageSlotInMap(base, me)).toBigInt()] : []);
  return { publicMap: layout.public_balances?.slot.toBigInt(), privateSlots };
}

type Simulated = Awaited<ReturnType<AzwallWallet['simulateTx']>>;

async function publicDelta(wallet: AzwallWallet, token: string, me: AztecAddress, map: bigint, sim: Simulated): Promise<bigint> {
  const contract = AztecAddress.fromStringUnsafe(token);
  const slot = await deriveStorageSlotInMap(map, me);
  const leaf = await computePublicDataTreeLeafSlot(contract, slot);
  const write = sim.publicOutput?.txEffect.publicDataWrites.find(w => w.leafSlot.equals(leaf));
  if (!write) return 0n;
  const before = await wallet.node.getPublicStorageAt('latest', contract, slot);
  return write.value.toBigInt() - before.toBigInt();
}

async function spentBy(wallet: AzwallWallet, token: string, me: string, slots: Slots, nullifiers: Set<bigint>): Promise<bigint> {
  const notes = await wallet.notesOf(token, me);
  return notes
    .filter(n => nullifiers.has(n.siloedNullifier.toBigInt()) && slots.privateSlots.has(n.storageSlot.toBigInt()))
    .reduce((sum, n) => sum + (n.note.items[0]?.toBigInt() ?? 0n), 0n);
}

type SendOpts = { from: AztecAddress; fee?: { gasSettings?: { maxPriorityFeesPerGas?: GasFees } }; additionalScopes?: AztecAddress[] };

async function simulate(wallet: AzwallWallet, args: unknown[]): Promise<{ sim: Simulated; opts: SendOpts }> {
  const [payload, opts] = (await parseArgs('sendTx', args)) as [ExecutionPayload, SendOpts];
  const sim = await wallet.simulateTx(payload, { from: opts.from, fee: opts.fee as never, additionalScopes: opts.additionalScopes, skipTxValidation: true });
  return { sim, opts };
}

const FEE_JUICE_DECIMALS = 18;

/** An estimate in token units, to three significant digits: 4970975790000000000n with 18 → "4.97". */
export function roughly(v: bigint, decimals: number): string {
  const digits = v.toString().length;
  const drop = BigInt(Math.max(0, digits - 3));
  const rounded = ((v + (drop ? 10n ** drop / 2n : 0n)) / 10n ** drop) * 10n ** drop;
  return formatAmount(rounded, decimals);
}

/** The fee line of an approval: about how much Fee Juice the transaction costs at today's fees, and who pays it. */
export function feeLine(fee: bigint, payer: Payer, tip = 0n): string {
  const shown = roughly(fee, FEE_JUICE_DECIMALS);
  const amount = shown === '0' && fee > 0n ? 'less than 0.00000001' : `about ${shown}`;
  // The site's priority fee goes on top of the network's: said as the most it can come to.
  const upTo = tip > 0n ? `, up to ${roughly(fee + tip, FEE_JUICE_DECIMALS)} with the priority fee this site set` : '';
  const by = payer === 'sponsor' ? "paid by the network's sponsor" : payer === 'account' ? 'paid from your account' : `paid by ${payer.slice(0, 10)}…`;
  return `Fee: ${amount} Fee Juice${upTo}, ${by}`;
}

/**
 * The effects of the transaction `args` (a sendTx's arguments) for account `me`: its balance changes over the PXE's
 * tokens, and its fee at the node's current minimum gas prices.
 */
export async function simulateEffects(wallet: AzwallWallet, me: string, args: unknown[]): Promise<Effects> {
  const account = AztecAddress.fromStringUnsafe(me);
  const tokens = (await wallet.registeredTokens()).map(t => t.toLowerCase());
  const found = await Promise.all(tokens.map(async t => [t, await slotsOf(wallet, t, account)] as const));
  const slots = new Map(found.filter((e): e is readonly [string, Slots] => e[1] !== undefined));
  const { sim, opts } = await simulate(wallet, args);
  const nullifiers = new Set(sim.publicInputs.getNonEmptyNullifiers().map(n => n.toBigInt()));
  const created = createdFor(me, sim.privateExecutionResult.entrypoint as unknown as CallNotes, slots);
  const changes = await Promise.all(
    [...slots].map(async ([token, s]): Promise<BalanceChange[]> => {
      const pub = s.publicMap === undefined ? 0n : await publicDelta(wallet, token, account, s.publicMap, sim);
      const priv = (created.get(token) ?? 0n) - (await spentBy(wallet, token, me, s, nullifiers));
      return [{ token, kind: 'public' as const, delta: pub }, { token, kind: 'private' as const, delta: priv }].filter(c => c.delta !== 0n);
    }),
  );
  const fee = sim.gasUsed.billedGas.computeFee(await wallet.node.getCurrentMinFees()).toBigInt();
  const priority = opts.fee?.gasSettings?.maxPriorityFeesPerGas;
  const tip = priority ? sim.gasUsed.billedGas.computeFee(priority).toBigInt() : 0n;
  return { changes: changes.flat(), fee, tip };
}

const metas = new Map<string, TokenMeta>();

/** Symbol and decimals of each token in `changes`, read once per token. */
export async function metaOf(wallet: AzwallWallet, me: string, changes: BalanceChange[]): Promise<Map<string, TokenMeta>> {
  for (const token of new Set(changes.map(c => c.token))) {
    if (metas.has(token)) continue;
    const info = await tokenInfo(wallet, token, me).catch(err => (log('dapp', `token ${token}: ${String(err)}`, 'warn'), undefined));
    if (info) metas.set(token, { symbol: info.symbol, decimals: info.decimals });
  }
  return metas;
}
