// Calls an aztec.js Wallet method with JSON arguments, the way Aztec's own JSON-RPC wallet server does: the
// arguments are validated against WalletSchema, then the result is serialised with jsonStringify.
import { WalletSchema } from '@aztec/aztec.js/wallet';
import { jsonStringify } from '@aztec/foundation/json-rpc';
import { getSchemaParameters, getSchemaReturnType, parseWithOptionals, schemaHasMethod } from '@aztec/foundation/schemas';

import type { AzwallWallet } from '../wallet';

/** Returns the JSON result, or undefined for methods declared `void` (a dApp's schema rejects anything else). */
export async function rawCall(wallet: AzwallWallet, method: string, args: unknown[]): Promise<unknown> {
  if (!schemaHasMethod(WalletSchema, method)) throw new Error(`not a Wallet method: ${method}`);
  const schema = WalletSchema[method as keyof typeof WalletSchema];
  const parsed = await parseWithOptionals(args, getSchemaParameters(schema));
  const fn = (wallet as unknown as Record<string, (...a: unknown[]) => Promise<unknown>>)[method];
  const result = await fn.apply(wallet, parsed);
  if ((getSchemaReturnType(schema) as { def?: { type?: string } }).def?.type === 'void') return undefined;
  return JSON.parse(jsonStringify(result));
}
