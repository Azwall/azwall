import { describe, expect, it } from 'vitest';

import { type CallNotes, changeLines, createdFor, feeLine, formatAmount } from '../src/dapp/effects';
import { noteOrigin, recordNoteOwners } from '../src/note_owners';

const me = '0xAA';
const token = '0xcc';
const slot = 3n;
const field = (v: bigint) => ({ toBigInt: () => v });

/** A stand-in for the PXE's private execution oracle: notifyCreatedNote(owner, slot, …, counter) adds a note. */
class FakeOracle {
  newNotes: object[] = [];
  callContext = { contractAddress: { toString: () => token } };
  // The PXE's order: owner, storageSlot, randomness, noteTypeId, noteItems, noteHash, counter.
  notifyCreatedNote(_owner: string, storageSlot: bigint, _r: unknown, _t: unknown, items: bigint[], _hash?: unknown, _counter?: number) {
    this.newNotes.push({ note: { items: items.map(field) }, storageSlot: field(storageSlot) });
  }
}
recordNoteOwners(FakeOracle);

function call(notes: [owner: string, value: bigint, counter: number][], squashed: number[] = []): CallNotes {
  const oracle = new FakeOracle();
  for (const [owner, value, counter] of notes) oracle.notifyCreatedNote(owner, slot, 0, 0, [value], 0, counter);
  notes.forEach(([owner, , counter], i) => expect(noteOrigin(oracle.newNotes[i])).toEqual({ owner: owner.toLowerCase(), contract: token, counter }));
  return { newNotes: oracle.newNotes as CallNotes['newNotes'], noteHashNullifierCounterMap: new Map(squashed.map(c => [c, c + 1])), nestedExecutionResults: [] };
}

describe('balance changes', () => {
  it('writes amounts in token units', () => {
    expect(formatAmount(5_000_000n, 6)).toBe('5');
    expect(formatAmount(-2_475_000_000_000_000n, 18)).toBe('0.002475');
    expect(formatAmount(1n, 18)).toBe('0');
    expect(formatAmount(123_456_789n, 0)).toBe('123456789');
  });

  it('lists what leaves the account, then what comes in', () => {
    const [usdc, weth, other] = ['0x0a', '0x0b', '0x0c'].map(p => p + '1'.repeat(62) + '2345');
    const meta = new Map([[usdc, { symbol: 'USDC', decimals: 6 }], [weth, { symbol: 'WETH', decimals: 18 }]]);
    const lines = changeLines(
      [
        { token: weth, kind: 'private', delta: 2_475_000_000_000_000n },
        { token: usdc, kind: 'public', delta: -5_000_000n },
        { token: other, kind: 'public', delta: -7n },
      ],
      meta,
      new Set([usdc, weth, other]),
    );
    // Each with its address: a symbol is whatever the token's deployer chose.
    expect(lines).toEqual([
      'You send 5 USDC (public, 0x0a1111…2345)',
      'You send 7 of an unknown token (public, 0x0c1111…2345)',
      'You receive 0.002475 WETH (private, 0x0b1111…2345)',
    ]);
  });

  // 2026-10-28 store-release audit (F9): a site's own "USDC" must not read as the real one, nor its symbol forge lines.
  it('marks a token the user does not follow, and keeps a symbol to one short line', () => {
    const [real, fake] = ['0x0a', '0x0d'].map(p => p + '1'.repeat(62) + '2345');
    const meta = new Map([[real, { symbol: 'USDC', decimals: 6 }], [fake, { symbol: 'USDC)\nYou receive 1000 ETH', decimals: 6 }]]);
    const lines = changeLines(
      [
        { token: real, kind: 'public', delta: -100_000_000n },
        { token: fake, kind: 'public', delta: 100_000_000n },
      ],
      meta,
      new Set([real]),
    );
    expect(lines[0]).toBe('You send 100 USDC (public, 0x0a1111…2345)');
    expect(lines[1]).toContain('not a token you follow');
    expect(lines[1]).not.toContain('\n');
  });

  it('says about what the fee comes to and who pays it', () => {
    expect(feeLine(4_970_975_790_000_000_000n, 'sponsor')).toBe("Fee: about 4.97 Fee Juice, paid by the network's sponsor");
    expect(feeLine(1_235_500_000_000_000n, 'sponsor')).toBe("Fee: about 0.00124 Fee Juice, paid by the network's sponsor");
    expect(feeLine(2n * 10n ** 18n, 'account')).toBe('Fee: about 2 Fee Juice, paid from your account');
    // A priority fee set by the site: the most it can add, said next to the network's fee.
    expect(feeLine(2n * 10n ** 18n, 'account', 10n ** 18n)).toBe('Fee: about 2 Fee Juice, up to 3 with the priority fee this site set, paid from your account');
    expect(feeLine(5n, `0x${'ab'.repeat(32)}`)).toBe('Fee: less than 0.00000001 Fee Juice, paid by 0xabababab…');
  });

  it('counts the notes created for the account, not those for others or at other slots', () => {
    const root = call([[me, 40n, 1], ['0xbb', 60n, 2]]);
    root.nestedExecutionResults.push(call([[me, 5n, 3]]));
    const slots = new Map([[token, { privateSlots: new Set([slot]) }]]);
    expect(createdFor(me, root, slots).get(token)).toBe(45n);
    expect(createdFor(me, root, new Map([[token, { privateSlots: new Set([9n]) }]])).size).toBe(0);
  });

  it('leaves out a note the transaction spends again', () => {
    // Note 1 is nullified by a later call of the same transaction: it never lands.
    const root = call([[me, 40n, 1], [me, 2n, 2]]);
    root.nestedExecutionResults.push(call([], [1]));
    expect(createdFor(me, root, new Map([[token, { privateSlots: new Set([slot]) }]])).get(token)).toBe(2n);
  });
});
