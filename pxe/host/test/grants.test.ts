import { FunctionSelector, FunctionType, type ContractArtifact } from '@aztec/stdlib/abi';
import { describe, expect, it } from 'vitest';

import { trustFeePayers } from '../src/dapp/calls';
import { added, addedLines, grantsOf, merge, permit } from '../src/dapp/grants';
import { NotGranted } from '../src/dapp/policy';

const fn = (name: string) => ({ name, functionType: FunctionType.PRIVATE, parameters: [] });
const token = `0x${'11'.repeat(32)}`;
const dex = `0x${'22'.repeat(32)}`;
const sponsor = `0x${'33'.repeat(32)}`;
const unknown = `0x${'99'.repeat(32)}`;
const artifacts: Record<string, ContractArtifact> = {
  [token]: { name: 'Token', functions: [fn('balance_of_private'), fn('transfer')] } as unknown as ContractArtifact,
  [dex]: { name: 'Dex', functions: [fn('swap'), fn('get_pool')] } as unknown as ContractArtifact,
  [sponsor]: { name: 'SponsoredFPC', functions: [fn('sponsor_unconditionally')] } as unknown as ContractArtifact,
};
const artifactOf = async (a: string) => artifacts[a];

async function call(to: string, name: string) {
  const selector = await FunctionSelector.fromNameAndParameters(name, []);
  return { name, to, selector: selector.toString(), args: [] };
}

const manifest = [
  { type: 'accounts', canGet: true, canCreateAuthWit: false },
  { type: 'simulation', transactions: { scope: [{ contract: dex, function: 'get_pool' }] }, utilities: { scope: [{ contract: token, function: 'balance_of_private' }] } },
  { type: 'transaction', scope: [{ contract: dex, function: '*' }] },
  { type: 'data', privateEvents: { contracts: [token] } },
];

describe('grants', () => {
  it('lets a site call only what it was granted', async () => {
    const g = grantsOf(manifest);
    await permit(g, 'getAccounts', [], artifactOf);
    await expect(permit(g, 'createAuthWit', [], artifactOf)).rejects.toThrow(NotGranted);
    await permit(g, 'simulateTx', [{ calls: [await call(dex, 'get_pool')] }], artifactOf);
    await expect(permit(g, 'simulateTx', [{ calls: [await call(dex, 'swap')] }], artifactOf)).rejects.toThrow('not allowed to simulate swap on Dex');
    await permit(g, 'executeUtility', [await call(token, 'balance_of_private')], artifactOf);
    await expect(permit(g, 'executeUtility', [await call(dex, 'get_pool')], artifactOf)).rejects.toThrow(NotGranted);
    await permit(g, 'sendTx', [{ calls: [await call(dex, 'swap')] }], artifactOf);
    await expect(permit(g, 'sendTx', [{ calls: [await call(token, 'transfer')] }], artifactOf)).rejects.toThrow(NotGranted);
    await permit(g, 'getPrivateEvents', [{}, { contractAddress: token.toUpperCase().replace('0X', '0x') }], artifactOf);
    await expect(permit(g, 'getPrivateEvents', [{}, { contractAddress: dex }], artifactOf)).rejects.toThrow(NotGranted);
    await permit(g, 'registerContract', [], artifactOf);
  });

  it('trusts only a verified function name, and lets the network sponsor pay', async () => {
    const g = grantsOf([{ type: 'transaction', scope: [{ contract: '*', function: 'swap' }, { contract: unknown, function: 'claim' }] }]);
    // The wallet does not know `unknown`: its label proves nothing, so only a whole-contract grant would cover it.
    await expect(permit(g, 'sendTx', [{ calls: [{ name: 'claim', to: unknown, selector: '0x01020304' }] }], artifactOf)).rejects.toThrow(NotGranted);
    trustFeePayers([sponsor]);
    await permit(g, 'sendTx', [{ calls: [await call(dex, 'swap'), await call(sponsor, 'sponsor_unconditionally')] }], artifactOf);
    trustFeePayers([]);
  });

  it('asks again only for what a request adds', async () => {
    const g = grantsOf(manifest);
    const same = added(g, grantsOf([{ type: 'transaction', scope: [{ contract: dex.replace('0x22', '0x0022'), function: 'swap' }] }]));
    expect(same).toEqual({ flags: [], scopes: [] });
    const more = added(g, grantsOf([
      { type: 'accounts', canGet: true, canCreateAuthWit: true },
      { type: 'simulation', transactions: { scope: [{ contract: dex, function: '*' }] } },
    ]));
    expect(more.flags).toEqual(['createAuthWit']);
    const nameOf = async (a: string) => artifacts[a]?.name;
    expect(await addedLines(more, nameOf)).toEqual(['Ask you for authorizations', 'Simulate any function of Dex']);
    const wider = merge(g, grantsOf([{ type: 'simulation', transactions: { scope: '*' } }]));
    expect(added(wider, grantsOf([{ type: 'simulation', transactions: { scope: [{ contract: dex, function: '*' }] } }]))).toEqual({ flags: [], scopes: [] });
  });
});
