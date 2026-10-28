// Calls an aztec.js Wallet method with JSON arguments, the way Aztec's own JSON-RPC wallet server does: the
// arguments are validated against WalletSchema, then the result is serialised with jsonStringify.
import { WalletSchema } from '@aztec/aztec.js/wallet';
import { jsonStringify } from '@aztec/foundation/json-rpc';
import { getSchemaParameters, getSchemaReturnType, parseWithOptionals, schemaHasMethod } from '@aztec/foundation/schemas';

import type { AzwallWallet } from '../wallet';

/** A Wallet method's JSON arguments, parsed as the method takes them. */
export async function parseArgs(method: string, args: unknown[]): Promise<unknown[]> {
  if (!schemaHasMethod(WalletSchema, method)) throw new Error(`not a Wallet method: ${method}`);
  return parseWithOptionals(args, getSchemaParameters(WalletSchema[method as keyof typeof WalletSchema]));
}

/**
 * A method's arguments as the wallet will run them: parsed by its schema, then back to JSON. What the schema drops
 * (unknown keys, the members of a union it did not pick) is gone, and addresses and fields take one form, so the
 * review, the checks on what a site may do, and the call itself all read the same thing. A site's request is only
 * ever handled in this form.
 */
export async function canonicalArgs(method: string, args: unknown[]): Promise<unknown[]> {
  const parsed = await parseArgs(method, args);
  const json = JSON.parse(jsonStringify(parsed)) as unknown[];
  // Optional arguments left out stay left out: JSON has no undefined, and the schema refuses null for them.
  while (json.length && parsed[json.length - 1] === undefined) json.pop();
  return json;
}

/** Returns the JSON result, or undefined for methods declared `void` (a dApp's schema rejects anything else). */
export async function rawCall(wallet: AzwallWallet, method: string, args: unknown[]): Promise<unknown> {
  const parsed = await parseArgs(method, args);
  const schema = WalletSchema[method as keyof typeof WalletSchema];
  const fn = (wallet as unknown as Record<string, (...a: unknown[]) => Promise<unknown>>)[method];
  const result = await fn.apply(wallet, parsed);
  if ((getSchemaReturnType(schema) as { def?: { type?: string } }).def?.type === 'void') return undefined;
  return JSON.parse(jsonStringify(result));
}
