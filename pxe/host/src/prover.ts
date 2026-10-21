// Delegates the Chonk proof to the app's native prover (rust/azwall-prover, the network's Barretenberg release).
// Kernel witness generation stays here, exactly as in the stock prover; only `createChonkProof` changes.
import { serializeWitness } from '@aztec/noir-noirc_abi';
import { BBLazyPrivateKernelProver } from '@aztec/bb-prover/client/lazy';
import type { CircuitSimulator } from '@aztec/simulator/client';
import type { PrivateExecutionStep } from '@aztec/stdlib/kernel';
import { ChonkProofWithPublicInputs } from '@aztec/stdlib/proofs';
import { type Bridge, decodeResponse, encodeRequest, fromBase64, toBase64 } from '@azwall/pxe-shared';

import { log } from './log';
import { stepKind } from './version';

export class NativeKernelProver extends BBLazyPrivateKernelProver {
  constructor(
    simulator: CircuitSimulator,
    private readonly bridge: Bridge,
    private readonly networkId: string,
  ) {
    super(simulator, {});
  }

  override async createChonkProof(executionSteps: PrivateExecutionStep[]): Promise<ChonkProofWithPublicInputs> {
    const t0 = performance.now();
    // Bytecode as stored (gzipped) and serializeWitness output (gzipped): Rust inflates both.
    const request = encodeRequest(
      executionSteps.map(s => ({
        kind: stepKind(s),
        name: s.functionName,
        bytecode: s.bytecode,
        witness: serializeWitness(s.witness),
        vk: s.vk,
      })),
    );
    const reply = await this.bridge.callNative<{ response: string }>('prove', {
      network: this.networkId,
      request: toBase64(request),
    });
    const { fields, compressed, meta } = decodeResponse(fromBase64(reply.response));
    const seconds = ((performance.now() - t0) / 1000).toFixed(1);
    log('prover', `Chonk proof of ${executionSteps.length} circuits in ${seconds} s (${JSON.stringify(meta.timings ?? {})})`);
    this.bridge.emit('proved', { circuits: executionSteps.length, ms: performance.now() - t0 });
    const proof = ChonkProofWithPublicInputs.fromBufferArray(fields.map(f => Buffer.from(f)));
    proof.compressedProof = Buffer.from(compressed);
    return proof;
  }
}
