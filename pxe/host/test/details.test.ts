import { FunctionSelector, FunctionType, type ContractArtifact } from '@aztec/stdlib/abi';
import { describe, expect, it } from 'vitest';

import {
  RAW_LIMIT,
  authwitSections,
  capabilitySections,
  connectSections,
  full,
  rawSection,
  txSections,
  typeLabel,
} from '../src/dapp/details';

const transfer = {
  name: 'transfer',
  functionType: FunctionType.PRIVATE,
  parameters: [
    { name: 'to', type: { kind: 'field' }, visibility: 'private' },
    { name: 'amount', type: { kind: 'integer', sign: 'unsigned', width: 128 }, visibility: 'private' },
  ],
};
const artifact = { name: 'Token', functions: [transfer] } as unknown as ContractArtifact;
const token = `0x${'11'.repeat(32)}`;
const unknown = `0x${'99'.repeat(32)}`;
const artifactOf = async (a: string) => (a === token ? artifact : undefined);
const nameOf = async (a: string) => (await artifactOf(a))?.name;

async function call() {
  const selector = await FunctionSelector.fromNameAndParameters(transfer.name, transfer.parameters as never);
  return { name: 'transfer', to: token, selector: selector.toString(), type: 'private', args: ['0x05', '0x0f4240'] };
}

const value = (sections: { rows: { label: string; value: string }[] }[], label: string) =>
  sections.flatMap(s => s.rows).find(r => r.label === label)?.value;

describe('approval details', () => {
  it('spells values and types out in full', () => {
    expect(full({ a: [1n, true], to: token })).toBe(`{a: [1, true], to: ${token}}`);
    expect(typeLabel({ kind: 'integer', sign: 'unsigned', width: 128 } as never)).toBe('u128');
    expect(typeLabel({ kind: 'array', length: 3, type: { kind: 'field' } } as never)).toBe('[Field; 3]');
    expect(typeLabel({ kind: 'struct', path: 'aztec::protocol_types::AztecAddress', fields: [] } as never)).toBe('AztecAddress');
  });

  it('lists every contract and function a site asks for, with whole addresses', async () => {
    const manifest = {
      version: '1.0',
      metadata: { name: 'Palmyr DEX', version: '1.0.0' },
      capabilities: [
        { type: 'accounts', canGet: true, canCreateAuthWit: true },
        { type: 'contracts', contracts: [token, unknown], canRegister: true },
        { type: 'transaction', scope: [{ contract: token, function: '*' }, { contract: unknown, function: 'swap' }] },
        { type: 'simulation', transactions: { scope: '*' } },
      ],
    };
    const sections = await capabilitySections(manifest, '0xme', nameOf);
    expect(sections.map(s => s.title)).toEqual([
      'What the site says about itself',
      'Your account',
      'Contracts',
      'Transactions (each one still asks you)',
      'Simulations (nothing is proven or sent)',
    ]);
    expect(value(sections, 'Name')).toBe('Palmyr DEX');
    expect(value(sections, 'May ask you for authorizations')).toBe('Yes');
    expect(value(sections, 'Token')).toBe(token);
    expect(value(sections, 'Contract')).toBe(unknown);
    expect(value(sections, 'Token: any function')).toBe(token);
    expect(value(sections, 'Contract: swap')).toBe(unknown);
    expect(value(sections, 'Transaction')).toBe('Any function of any contract');
  });

  it('shows each call of a transaction with typed arguments, then its fee and attachments', async () => {
    const payload = { calls: [await call()], authWitnesses: [{ requestHash: '0xabc' }] };
    const sections = await txSections([payload, { from: '0xme', fee: { gasSettings: { gasLimits: { daGas: 10 } } } }], artifactOf);
    expect(sections[0].title).toBe('Call 1: transfer');
    expect(value(sections, 'Address')).toBe(token);
    expect(value(sections, 'Kind')).toBe('private');
    expect(value(sections, 'to · Field')).toBe('5');
    expect(value(sections, 'amount · u128')).toBe('1000000');
    expect(value(sections, 'Sent as')).toBe('0xme');
    expect(value(sections, 'Fee › gasSettings › gasLimits › daGas')).toBe('10');
    expect(value(sections, 'Authorization 1')).toBe('0xabc');
  });

  it('shows raw fields when the wallet does not know the contract', async () => {
    const sections = await txSections([{ calls: [{ name: 'claim', to: unknown, selector: '0x01020304', args: ['0x07'] }] }, {}], artifactOf);
    expect(value(sections, 'Contract')).toContain('does not know');
    expect(value(sections, 'Argument 1 (raw field)')).toBe('0x07');
  });

  it('describes authorizations and connections', async () => {
    const readable = await authwitSections(['0xme', { caller: '0xdex', call: await call() }], artifactOf);
    expect(value(readable, 'Who may use it')).toBe('0xdex');
    expect(readable[1].title).toBe('Call it covers: transfer');
    expect(value(await authwitSections(['0xme', '0xdead'], artifactOf), 'Message hash')).toBe('0xdead');
    const connect = connectSections('https://a.example', 'palmyr', 'Palmyr', { chainId: '0xaa36a7', version: '0x287cb646' });
    expect(value(connect, 'Calls itself')).toBe('Palmyr');
    expect(value(connect, 'Rollup version')).toBe('679261766');
    expect(value(connect, 'L1 chain id')).toBe('11155111');
  });

  it('caps the raw request', () => {
    expect(rawSection({ n: 1n }).rows[0].value).toContain('"n": "1"');
    expect(rawSection('x'.repeat(RAW_LIMIT * 2)).rows[0].value).toContain('more characters');
  });
});
