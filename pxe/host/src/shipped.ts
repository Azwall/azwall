// The contract classes the wallet ships an artifact for: the Token, the Sponsored FPC and Schnorr accounts. A class id
// binds the selectors, bytecode and return types of an artifact, not its parameter names nor its storage layout: a
// site could register a copy of one of these classes with `from` and `to` swapped and its balance slots pointing
// nowhere, under the same id, and every later review of a real token would read through it (a transfer from the user
// shown as one to them, with no balance change). So these classes are only ever read through the wallet's own
// artifact, and a site registering one gets the wallet's stored instead of its own.
import { SchnorrAccountContractArtifact, SchnorrInitializerlessAccountContractArtifact } from '@aztec/accounts/schnorr';
import { SponsoredFPCContractArtifact } from '@aztec/noir-contracts.js/SponsoredFPC';
import { TokenContractArtifact } from '@aztec/noir-contracts.js/Token';
import type { ContractArtifact } from '@aztec/stdlib/abi';
import { getContractClassFromArtifact } from '@aztec/stdlib/contract';

let shipped: Promise<Map<string, ContractArtifact>> | undefined;

/** Class id (lower case) → the wallet's own artifact of that class. */
function shippedArtifacts(): Promise<Map<string, ContractArtifact>> {
  shipped ??= (async () => {
    const artifacts = [
      TokenContractArtifact,
      SponsoredFPCContractArtifact,
      SchnorrAccountContractArtifact,
      SchnorrInitializerlessAccountContractArtifact,
    ];
    const ids = await Promise.all(artifacts.map(async a => (await getContractClassFromArtifact(a)).id.toString().toLowerCase()));
    return new Map(ids.map((id, i) => [id, artifacts[i]]));
  })();
  return shipped;
}

/** The wallet's own artifact for the class `classId`, if it ships one. */
export async function shippedArtifact(classId: string): Promise<ContractArtifact | undefined> {
  return (await shippedArtifacts()).get(classId.toLowerCase());
}

/** `artifact` itself, or the wallet's own when it is of a class the wallet ships. */
export async function trustedArtifact(artifact: ContractArtifact): Promise<ContractArtifact> {
  const { id } = await getContractClassFromArtifact(artifact);
  return (await shippedArtifact(id.toString())) ?? artifact;
}
