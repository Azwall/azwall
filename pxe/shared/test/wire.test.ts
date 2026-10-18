import { describe, expect, it } from 'vitest';

import { amount, asParams, hex, int, str, strings } from '../src/params';
import { decodeResponse, encodeRequest, fromBase64, toBase64 } from '../src/wire';

function u32(v: number) {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, v, true);
  return [...b];
}

describe('encodeRequest', () => {
  it('follows the AZS2 layout of rust/azwall-prover/src/wire.rs', () => {
    const req = encodeRequest([{ kind: 3, name: 'ab', bytecode: Uint8Array.of(1), witness: Uint8Array.of(2, 2), vk: Uint8Array.of(9) }]);
    expect([...req]).toEqual([
      ...[0x41, 0x5a, 0x53, 0x32],
      ...u32(1),
      ...u32(3),
      ...u32(2), 0x61, 0x62,
      ...u32(1), 1,
      ...u32(2), 2, 2,
      ...u32(1), 9,
    ]);
  });
});

describe('decodeResponse', () => {
  const meta = new TextEncoder().encode('{"release":"6.0.0-rc.6"}');
  const response = Uint8Array.from([
    ...[0x41, 0x5a, 0x50, 0x31],
    ...u32(2),
    ...new Array(32).fill(1),
    ...new Array(32).fill(2),
    ...u32(3), 7, 8, 9,
    ...u32(meta.length), ...meta,
  ]);

  it('reads fields, compressed proof and meta', () => {
    const r = decodeResponse(response);
    expect(r.fields.map(f => f[0])).toEqual([1, 2]);
    expect([...r.compressed]).toEqual([7, 8, 9]);
    expect(r.meta).toEqual({ release: '6.0.0-rc.6' });
  });

  it('rejects bad magic, truncation and trailing bytes', () => {
    expect(() => decodeResponse(Uint8Array.of(0, 0, 0, 0))).toThrow('magic');
    expect(() => decodeResponse(response.slice(0, 40))).toThrow('truncated');
    expect(() => decodeResponse(Uint8Array.from([...response, 0]))).toThrow('trailing');
  });
});

describe('base64', () => {
  it('round-trips buffers larger than one chunk', () => {
    const data = Uint8Array.from({ length: 100_000 }, (_, i) => i % 251);
    expect(fromBase64(toBase64(data))).toEqual(data);
  });
});

describe('params', () => {
  it('validates each kind of field', () => {
    const p = asParams({ s: 'x', n: 3, a: '1000000000000000000000', h: '0xABCD', l: ['a'] });
    expect(str(p, 's')).toBe('x');
    expect(int(p, 'n')).toBe(3);
    expect(amount(p, 'a')).toBe(10n ** 21n);
    expect(hex(p, 'h', 2)).toBe('0xabcd');
    expect(strings(p, 'l')).toEqual(['a']);
  });

  it('names the failing field', () => {
    const p = asParams({ a: '0', b: '1.5', h: 'xyz', n: -1 });
    expect(() => amount(p, 'a')).toThrow('a must be positive');
    expect(() => amount(p, 'b')).toThrow('b must be a decimal');
    expect(() => hex(p, 'h')).toThrow('h must be hex');
    expect(() => int(p, 'n')).toThrow('n must be');
    expect(() => str(p, 'missing')).toThrow('missing must be');
    expect(() => asParams([])).toThrow();
  });
});
