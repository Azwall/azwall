// What differs between the Aztec versions a PXE host is built for. Everything else is pxe/host, copied here
// (with this version's package names) by pxe/scripts/sync-host.mjs.
import { createAztecNodeClient, type AztecNode } from '@aztec-labs/aztec.js/node';
import type { Fr } from '@aztec-labs/aztec.js/fields';
import { type AbiType, decodeFromAbi } from '@aztec-labs/stdlib/abi';
import type { PrivateExecutionStep } from '@aztec-labs/stdlib/kernel';

export const AZTEC_VERSION = '6.0.0-rc.3';

/** The node's JSON-RPC client; 6.x can cap how many calls one batch carries. */
export function nodeClient(url: string, maxBatchSize: number): AztecNode {
  return createAztecNodeClient(url, { maxBatchSize });
}

/** The circuit kind of a proving step, as the native prover's binding for this version expects it. */
export function stepKind(step: PrivateExecutionStep): number {
  return step.kind;
}

/** Decodes flattened fields as one value of `type` (6.x takes a single type). */
export function decodeStruct(type: AbiType, fields: Fr[]): unknown {
  return decodeFromAbi(type, fields);
}
