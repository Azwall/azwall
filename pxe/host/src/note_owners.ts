// Who each note a simulation creates belongs to. The PXE knows it while executing (notifyCreatedNote receives the
// owner) but leaves it out of the notes the result lists; without it the wallet could not tell the change a
// transaction returns to the account from what it sends away. Recorded per note object of the result, so concurrent
// simulations (a dApp's balance reads) never mix.
import { PrivateExecutionOracle } from '@aztec/pxe/simulator';

export type NoteOrigin = { owner: string; contract: string; counter: number };

const origins = new WeakMap<object, NoteOrigin>();
const patched = new WeakSet<object>();

type OracleInternals = {
  newNotes: object[];
  callContext: { contractAddress: { toString(): string } };
  notifyCreatedNote(...args: unknown[]): unknown;
};

/** Starts recording note owners; idempotent. */
export function recordNoteOwners(oracle: { prototype: unknown } = PrivateExecutionOracle): void {
  const proto = oracle.prototype as OracleInternals;
  if (patched.has(proto)) return;
  patched.add(proto);
  const original = proto.notifyCreatedNote;
  // notifyCreatedNote(owner, storageSlot, randomness, noteTypeId, noteItems, noteHash, counter)
  proto.notifyCreatedNote = function (this: OracleInternals, ...args: unknown[]) {
    const result = original.apply(this, args);
    const note = this.newNotes.at(-1);
    if (note) origins.set(note, { owner: String(args[0]).toLowerCase(), contract: this.callContext.contractAddress.toString().toLowerCase(), counter: Number(args[6]) });
    return result;
  };
}

/** The owner, contract and side-effect counter of a note a simulation created, if it was recorded. */
export const noteOrigin = (note: object): NoteOrigin | undefined => origins.get(note);
