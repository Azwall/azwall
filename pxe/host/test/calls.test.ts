import { FunctionSelector, FunctionType, type ContractArtifact } from '@aztec/stdlib/abi';
import { describe, expect, it, vi } from 'vitest';

import { describeAuthWit, describeTx, LINE_LIMIT, oneLine, readCall, show, trustFeePayers } from '../src/dapp/calls';
import { log } from '../src/log';

vi.mock('../src/log', () => ({ log: vi.fn() }));

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
const artifactOf = async (a: string) => (a === token ? artifact : undefined);

async function call(name: string) {
  const selector = await FunctionSelector.fromNameAndParameters(transfer.name, transfer.parameters as never);
  return { name, to: token, selector: selector.toString(), args: ['0x05', '0x0f4240'] };
}

/** A site's own contract with a function named like the Sponsored FPC's. */
const sponsorLike = { ...transfer, name: 'sponsor_unconditionally', parameters: [] };
const fake = { name: 'FakeFPC', functions: [sponsorLike] } as unknown as ContractArtifact;
const site = `0x${'22'.repeat(32)}`;
const sponsor = `0x${'33'.repeat(32)}`;

describe('fee payments', () => {
  it('are told by the paying contract address, never by a function name', async () => {
    const artifacts = async (a: string) => (a === site || a === sponsor ? fake : undefined);
    const selector = (await FunctionSelector.fromNameAndParameters(sponsorLike.name, [])).toString();
    trustFeePayers([sponsor]);
    expect((await readCall({ to: site, selector, args: [] }, artifacts)).fee).toBe(false);
    expect((await readCall({ to: sponsor, selector, args: [] }, artifacts)).fee).toBe(true);
    trustFeePayers([]);
  });
});

describe('calls', () => {
  it('shows the function the selector runs, with its decoded arguments', async () => {
    const read = await readCall(await call('transfer'), artifactOf);
    expect(read.line).toBe('transfer(to: 5, amount: 1000000) on Token 0x111111…1111');
  });

  it('sums a long call up in about two lines, its first arguments then an ellipsis', async () => {
    const field = { kind: 'field' };
    const names = ['pool_id', 'x_to_y', 'amount_in', 'min_out', 'deadline', 'recipient', 'nonce'];
    const swap = { name: 'swap', functionType: FunctionType.PUBLIC, parameters: names.map(name => ({ name, type: field, visibility: 'public' })) };
    const dex = { name: 'Palmyr', functions: [], nonDispatchPublicFunctions: [swap] } as unknown as ContractArtifact;
    const selector = (await FunctionSelector.fromNameAndParameters(swap.name, swap.parameters as never)).toString();
    const big = `0x${'2a'.repeat(31)}`;
    const read = await readCall({ name: 'swap', to: token, selector, args: [big, '0x01', '0x2dc6c0', big, '0x6a', big, big] }, async () => dex);
    expect(read.line.length).toBeLessThanOrEqual(LINE_LIMIT);
    expect(read.line).toMatch(/^swap\(pool_id: \d{8}…\d{4}, x_to_y: 1, amount_in: 3000000, .*…\) on Palmyr 0x111111…1111$/);
    expect(show(10n ** 40n)).toBe('10000000…0000');
  });

  it('refuses a call whose label names another function', async () => {
    await expect(readCall(await call('sponsor_unconditionally'), artifactOf)).rejects.toThrow('labelled sponsor_unconditionally but runs transfer');
  });

  it('reads a call once, however often an approval shows it', async () => {
    let lookups = 0;
    const counting = async (a: string) => (lookups++, artifactOf(a));
    const c = await call('transfer');
    await readCall(c, counting);
    await readCall(c, counting);
    expect(lookups).toBe(1);
  });

  it('says when the wallet cannot verify a call', async () => {
    const read = await readCall({ name: 'claim', to: '0x99', selector: '0x01020304', args: [] }, artifactOf);
    expect(read.line).toContain('unverified');
  });

  it('logs why a contract could not be read, and still shows the call unverified', async () => {
    const failing = async () => {
      throw new Error('PXE closed');
    };
    const read = await readCall({ name: 'claim', to: token, selector: '0x01020304', args: [] }, failing);
    expect(read.line).toContain('unverified');
    expect(log).toHaveBeenCalledWith('dapp', expect.stringContaining('PXE closed'), 'warn');
  });

  it('describes transactions and authorizations', async () => {
    expect(await describeTx({ calls: [await call('transfer')], authWitnesses: [{}] }, artifactOf)).toEqual([
      'transfer(to: 5, amount: 1000000) on Token 0x111111…1111',
      '1 authorization(s) attached',
    ]);
    expect((await describeAuthWit({ caller: '0x77', call: await call('transfer') }, artifactOf))[1]).toContain('transfer(');
    expect((await describeAuthWit('0xdead', artifactOf))[0]).toContain('cannot read');
    const hashed = (await describeAuthWit({ consumer: '0x77', innerHash: '0xbeef' }, artifactOf))[0];
    expect(hashed).toBe('Lets 0x77 use an action the wallet cannot read (hash 0xbeef): approve only if you trust this site');
  });

  it('keeps arguments short', () => {
    expect(show(12n)).toBe('12');
    expect(show({ a: [1n, true] })).toBe('{a: [1, true]}');
    expect(show('x'.repeat(100)).length).toBeLessThanOrEqual(60);
  });
});

// 2026-10-28 store-release audit (F9): a site's words must not forge lines that read as the wallet's.
describe('a site\'s own words', () => {
  it('are one short line, without control or bidi characters', () => {
    expect(oneLine('Vote\n• You receive 1,000 USDC')).toBe('Vote • You receive 1,000 USDC');
    expect(oneLine('evil\u202Ecod.exe')).toBe('evil cod.exe');
    expect(oneLine('x'.repeat(200)).length).toBe(80);
  });

  it('name an unverified call on one line', async () => {
    const read = await readCall({ name: 'vote\nYou receive 1000 ETH', to: token }, async () => undefined);
    expect(read.line).not.toContain('\n');
  });
});

// 2026-10-29 re-audit (R3): names from a site's own artifact are the site's words too.
describe('a site\'s own contract', () => {
  it('cannot forge lines through its function, parameter or contract names', async () => {
    // A selector binds the function name, which may not hold whitespace, but bidi characters pass; parameter and
    // contract names are not in the selector at all.
    const forged = { ...transfer, name: 'claim\u202Edrawer', parameters: [{ ...transfer.parameters[0], name: 'to\n• You receive 1,000 USDC' }] };
    const site = { name: 'Vault\n• Fee: none', functions: [forged] } as unknown as ContractArtifact;
    const selector = (await FunctionSelector.fromNameAndParameters(forged.name, forged.parameters as never)).toString();
    const read = await readCall({ to: token, selector, args: ['0x05'] }, async () => site);
    expect(read.line).not.toMatch(/[\n\u202E]/);
    expect(read.contract).not.toContain('\n');
  });
});

// Readiness review (R3 residue): decoded values and field names are one line too.
describe('a decoded argument', () => {
  it('cannot forge lines through a struct field name', () => {
    expect(show({ 'to\n• You receive 1,000 USDC': 1n })).not.toMatch(/\n/);
    expect(show({ 'a\u202Eb': 1n })).not.toMatch(/\u202E/);
  });
});

