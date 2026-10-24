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
    const meta = new Map([['0x1', { symbol: 'USDC', decimals: 6 }], ['0x2', { symbol: 'WETH', decimals: 18 }]]);
    const lines = changeLines(
      [
        { token: '0x2', kind: 'private', delta: 2_475_000_000_000_000n },
        { token: '0x1', kind: 'public', delta: -5_000_000n },
        { token: '0x3', kind: 'public', delta: -7n },
      ],
      meta,
    );
    expect(lines).toEqual(['You send 5 USDC (public)', 'You send 7 token 0x3… (public)', 'You receive 0.002475 WETH (private)']);
  });

  it('says about what the fee comes to and who pays it', () => {
    expect(feeLine(1_234_500_000_000_000n, 'sponsor')).toBe("Fee: about 0.0012345 Fee Juice, paid by the network's sponsor");
    expect(feeLine(2n * 10n ** 18n, 'account')).toBe('Fee: about 2 Fee Juice, paid from your account');
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
