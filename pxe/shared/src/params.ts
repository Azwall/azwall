// Validation of the parameters native code sends. Native code is trusted, but a malformed call must fail
// with a message naming the field, not deep inside the PXE.

export type Params = Record<string, unknown>;

export function asParams(value: unknown): Params {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('parameters must be an object');
  return value as Params;
}

export function str(p: Params, key: string): string {
  const v = p[key];
  if (typeof v !== 'string' || v.length === 0) throw new Error(`${key} must be a non-empty string`);
  return v;
}

export function optStr(p: Params, key: string): string | undefined {
  return p[key] === undefined || p[key] === null ? undefined : str(p, key);
}

export function bool(p: Params, key: string): boolean {
  const v = p[key];
  if (typeof v !== 'boolean') throw new Error(`${key} must be a boolean`);
  return v;
}

export function int(p: Params, key: string): number {
  const v = p[key];
  if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error(`${key} must be a non-negative integer`);
  return v;
}

/** Token amounts cross the bridge as decimal strings of base units (they exceed 2^53). */
export function amount(p: Params, key: string): bigint {
  const v = str(p, key);
  if (!/^\d+$/.test(v)) throw new Error(`${key} must be a decimal integer string`);
  const n = BigInt(v);
  if (n === 0n) throw new Error(`${key} must be positive`);
  return n;
}

export function hex(p: Params, key: string, bytes?: number): string {
  const v = str(p, key);
  const body = v.startsWith('0x') ? v.slice(2) : v;
  if (!/^[0-9a-fA-F]+$/.test(body) || body.length % 2 !== 0) throw new Error(`${key} must be hex`);
  if (bytes !== undefined && body.length !== bytes * 2) throw new Error(`${key} must be ${bytes} bytes`);
  return `0x${body.toLowerCase()}`;
}

export function strings(p: Params, key: string): string[] {
  const v = p[key];
  if (!Array.isArray(v) || !v.every(x => typeof x === 'string')) throw new Error(`${key} must be an array of strings`);
  return v;
}

export function object(p: Params, key: string): Params {
  return asParams(p[key]);
}
