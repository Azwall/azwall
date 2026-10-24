import { FunctionSelector, FunctionType, type ContractArtifact } from '@aztec/stdlib/abi';
import { describe, expect, it } from 'vitest';

import { describeAuthWit, describeTx, readCall, show, trustFeePayers } from '../src/dapp/calls';

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
