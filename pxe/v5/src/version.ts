// What differs between the Aztec versions a PXE host is built for. Everything else is pxe/host, copied here
// (with this version's package names) by pxe/scripts/sync-host.mjs.
import { createAztecNodeClient, type AztecNode } from '@aztec/aztec.js/node';
import type { Fr } from '@aztec/aztec.js/fields';
import { type AbiType, decodeFromAbi } from '@aztec/stdlib/abi';
import type { PrivateExecutionStep } from '@aztec/stdlib/kernel';

export const AZTEC_VERSION = '5.1.0';

/** The node's JSON-RPC client; the 5.x one has no batch size option (rpcMaxBatch only applies to 6.x hosts). */
export function nodeClient(url: string, _maxBatchSize: number): AztecNode {
  return createAztecNodeClient(url);
}

/** The circuit kind of a proving step: 5.x steps carry none, and the 5.x binding only uses the step count. */
export function stepKind(_step: PrivateExecutionStep): number {
  return 0;
}

/** Decodes flattened fields as one value of `type` (5.x takes a list of types, one here). */
export function decodeStruct(type: AbiType, fields: Fr[]): unknown {
  return decodeFromAbi([type], fields);
}
