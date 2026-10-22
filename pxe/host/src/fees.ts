// How a network's fees are paid. Test networks run a Sponsored FPC that pays every fee; elsewhere (mainnet) there is
// none, and the account pays with its own Fee Juice (bridged from Ethereum as AZTEC), which is Aztec's default when no
// payment method is given.
import type { AztecAddress } from '@aztec/aztec.js/addresses';
import type { AztecNode } from '@aztec/aztec.js/node';

export type FeeMode = 'sponsored' | 'feeJuice';

/** Sponsored when the network has the Sponsored FPC deployed at its canonical address. */
export async function feeModeOf(node: Pick<AztecNode, 'getContract'>, sponsor: AztecAddress): Promise<FeeMode> {
  return (await node.getContract(sponsor)) ? 'sponsored' : 'feeJuice';
}
