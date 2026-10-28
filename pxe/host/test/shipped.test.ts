// From the 2026-10-28 store-release audit (F1): a site's copy of a class the wallet ships, with the same class id but
// its labels and storage layout changed, must never be what the wallet reads.
import { Fr } from '@aztec/foundation/curves/bn254';
import { TokenContractArtifact } from '@aztec/noir-contracts.js/Token';
import type { ContractArtifact } from '@aztec/stdlib/abi';
import { getContractClassFromArtifact } from '@aztec/stdlib/contract';
import { describe, expect, it } from 'vitest';

import { shippedArtifact, trustedArtifact } from '../src/shipped';

/** The Token artifact with `from` and `to` swapped in every function and its balance slots pointing nowhere. */
function crafted(): ContractArtifact {
  const copy = structuredClone(TokenContractArtifact) as ContractArtifact;
  const swap = (name: string) => (name === 'from' ? 'to' : name === 'to' ? 'from' : name);
  for (const fn of [...copy.functions, ...(copy.nonDispatchPublicFunctions ?? [])]) {
    for (const p of fn.parameters) p.name = swap(p.name);
  }
  Object.values(copy.storageLayout).forEach((field, i) => (field.slot = new Fr(0xdead00n + BigInt(i))));
  return copy;
}

describe('a contract class the wallet ships', () => {
  it('is read through the wallet\'s own artifact, never a site\'s copy under the same class id', async () => {
    const site = crafted();
    const [ours, theirs] = await Promise.all([getContractClassFromArtifact(TokenContractArtifact), getContractClassFromArtifact(site)]);
    expect(theirs.id.equals(ours.id)).toBe(true); // the premise: names and storage layout are not in the class id
    expect(await trustedArtifact(site)).toBe(TokenContractArtifact);
    expect(await shippedArtifact(ours.id.toString())).toBe(TokenContractArtifact);
  }, 60_000);

  it('leaves the artifact of any other class as the site sent it', async () => {
    const other = structuredClone(TokenContractArtifact) as ContractArtifact;
    other.functions = other.functions.slice(1); // another class
    expect(await trustedArtifact(other)).toBe(other);
  }, 60_000);
});
