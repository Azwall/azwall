// Winternitz one-time signatures over Poseidon2, the signing side of contracts/pq_account/src/wots.nr: same
// parameters, same domain separators, same hash (aztec-nr's poseidon2_hash_with_separator = Poseidon2 over the
// separator followed by the inputs). Any difference makes every signature fail in the contract.
import { poseidon2Hash } from '@aztec-labs/foundation/crypto/poseidon';
import { Fr } from '@aztec-labs/aztec.js/fields';

export const W = 4;
export const MSG_DIGITS = 127;
export const CHECKSUM_DIGITS = 5;
export const CHAINS = MSG_DIGITS + CHECKSUM_DIGITS;

const sep = (text: string) => new Fr(BigInt(`0x${Buffer.from(text).toString('hex')}`));
const SEP_SECRET = sep('azwall-wots-sk');
const SEP_CHAIN = sep('azwall-wots-ch');
const SEP_LEAF = sep('azwall-wots-lf');
const SEP_NODE = sep('azwall-wots-nd');

const hash = (separator: Fr, inputs: (Fr | number)[]) => poseidon2Hash([separator, ...inputs.map(i => (typeof i === 'number' ? new Fr(i) : i))]);

const chainStep = (x: Fr, chain: number, step: number) => hash(SEP_CHAIN, [x, chain, step]);

/** Base-4 digits of the message (127, from bit pairs) then of the checksum (5). */
export function digits(message: Fr): number[] {
  let m = message.toBigInt();
  const out: number[] = [];
  let checksum = 0;
  for (let i = 0; i < MSG_DIGITS; i++) {
    const d = Number(m & 3n);
    out.push(d);
    checksum += W - 1 - d;
    m >>= 2n;
  }
  for (let i = 0; i < CHECKSUM_DIGITS; i++) {
    out.push(checksum & 3);
    checksum >>= 2;
  }
  return out;
}

const secret = (seed: Fr, index: number, chain: number) => hash(SEP_SECRET, [seed, index, chain]);

/** Walks `steps` steps of chain `chain` from position `from`. */
async function walk(x: Fr, chain: number, from: number, to: number): Promise<Fr> {
  for (let step = from; step < to; step++) x = await chainStep(x, chain, step);
  return x;
}

export async function sign(seed: Fr, index: number, message: Fr): Promise<Fr[]> {
  const d = digits(message);
  return Promise.all(d.map(async (digit, i) => walk(await secret(seed, index, i), i, 0, digit)));
}

export async function leaf(seed: Fr, index: number): Promise<Fr> {
  const ends = await Promise.all(Array.from({ length: CHAINS }, async (_, i) => walk(await secret(seed, index, i), i, 0, W - 1)));
  return hash(SEP_LEAF, ends);
}

/** The leaves and every level above them, up to the root (levels[depth][0]). */
export async function buildTree(seed: Fr, depth: number, onProgress?: (done: number) => void): Promise<Fr[][]> {
  const leaves: Fr[] = [];
  for (let i = 0; i < 2 ** depth; i++) {
    leaves.push(await leaf(seed, i));
    onProgress?.(i + 1);
  }
  const levels = [leaves];
  while (levels[levels.length - 1].length > 1) {
    const below = levels[levels.length - 1];
    levels.push(await Promise.all(Array.from({ length: below.length / 2 }, (_, i) => hash(SEP_NODE, [below[2 * i], below[2 * i + 1]]))));
  }
  return levels;
}

/** Siblings of `index` from the bottom up. */
export function pathOf(levels: Fr[][], index: number): Fr[] {
  return levels.slice(0, -1).map((level, d) => level[(index >> d) ^ 1]);
}
