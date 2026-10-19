// The post-quantum account (contracts/pq_account) for aztec.js: its artifact, its constructor (the Merkle root of
// the one-time keys) and an auth witness provider that signs with WOTS.
import { DefaultAccountContract } from '@aztec-labs/accounts/defaults';
import type { AuthWitnessProvider } from '@aztec-labs/aztec.js/account';
import { loadContractArtifact, type ContractArtifact } from '@aztec-labs/aztec.js/abi';
import { Fr } from '@aztec-labs/aztec.js/fields';
import type { CompleteAddress } from '@aztec-labs/stdlib/contract';
import { AuthWitness } from '@aztec-labs/stdlib/auth-witness';

import { pathOf, sign } from './wots';

let artifact: Promise<ContractArtifact> | undefined;

/** Compiled by scripts/build-contracts.sh (aztec compile); loaded on first use, it weighs ~2 MB. */
function pqArtifact(): Promise<ContractArtifact> {
  return (artifact ??= import('./PqAccount.json').then(m => loadContractArtifact(m.default as never)));
}

/**
 * Signs with one fresh one-time key per message. The same message (simulation, then the real send) reuses its key:
 * signing it again reveals nothing new. A new message never gets a key already used.
 */
export class WotsAuthWitnessProvider implements AuthWitnessProvider {
  readonly #byMessage = new Map<string, number>();

  constructor(
    private readonly seed: Fr,
    private readonly levels: Fr[][],
    private nextIndex: number,
  ) {}

  async createAuthWit(messageHash: Fr): Promise<AuthWitness> {
    const key = messageHash.toString();
    let index = this.#byMessage.get(key);
    if (index === undefined) {
      index = this.nextIndex++;
      if (index >= this.levels[0].length) throw new Error('every one-time key of this account is used');
      this.#byMessage.set(key, index);
    }
    const signature = await sign(this.seed, index, messageHash);
    return new AuthWitness(messageHash, [new Fr(index), ...signature, ...pathOf(this.levels, index)]);
  }
}

export class PqAccountContract extends DefaultAccountContract {
  readonly #provider: WotsAuthWitnessProvider;

  constructor(seed: Fr, private readonly levels: Fr[][], nextIndex = 0) {
    super();
    this.#provider = new WotsAuthWitnessProvider(seed, levels, nextIndex);
  }

  getContractArtifact(): Promise<ContractArtifact> {
    return pqArtifact();
  }

  getInitializationFunctionAndArgs() {
    const root = this.levels[this.levels.length - 1][0];
    return Promise.resolve({ constructorName: 'constructor', constructorArgs: [root] });
  }

  getAuthWitnessProvider(_address: CompleteAddress): AuthWitnessProvider {
    return this.#provider;
  }
}
