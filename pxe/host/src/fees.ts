// How a network's fees are paid, as its registry entry declares (networks.json): by the network's Sponsored FPC on test
// networks, else with the account's own Fee Juice (bridged from Ethereum as AZTEC), which is Aztec's default when no
// payment method is given. Never inferred from the chain alone: anyone can deploy a contract at the Sponsored FPC's
// deterministic address, and an unfunded one would make every transaction fail.
import type { AztecAddress } from '@aztec/aztec.js/addresses';
import type { AztecNode } from '@aztec/aztec.js/node';

export type FeeMode = 'sponsored' | 'feeJuice';

/** What the registry declares; anything else (or nothing) means no sponsor. */
export function declaredFees(value: unknown): FeeMode {
  return value === 'sponsored' ? 'sponsored' : 'feeJuice';
}

/** Sponsored only when declared so and the Sponsored FPC is there; a declared sponsor missing on chain is reported. */
export async function feeModeOf(declared: FeeMode, node: Pick<AztecNode, 'getContract'>, sponsor: AztecAddress): Promise<FeeMode> {
  if (declared !== 'sponsored') return 'feeJuice';
  if (await node.getContract(sponsor)) return 'sponsored';
  throw new Error('this network declares a Sponsored FPC, but none is deployed at its address');
}
