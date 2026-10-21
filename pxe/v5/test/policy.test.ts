import { describe, expect, it } from 'vitest';

import { confine, NotGranted, samePage } from '../src/dapp/policy';

const me = '0xaa';
const other = '0xbb';
const contract = '0xcc';

describe('confine', () => {
  it('lets a site act as its account only', () => {
    const payload = { calls: [] };
    expect(confine('sendTx', [payload, { from: '0xAA', additionalScopes: [other, contract] }], me, [other])).toEqual([
      payload,
      { from: '0xAA', additionalScopes: [contract] },
    ]);
    expect(confine('simulateTx', [payload, { from: 'NO_FROM' }], me, [other])[1]).toEqual({ from: 'NO_FROM', additionalScopes: undefined });
    expect(() => confine('sendTx', [payload, { from: other }], me, [other])).toThrow(NotGranted);
    expect(() => confine('createAuthWit', [other, {}], me, [other])).toThrow(NotGranted);
    expect(confine('createAuthWit', [me, {}], me, [other])).toEqual([me, {}]);
  });

  it('reads for its account only', () => {
    expect(confine('getPrivateEvents', [{}, { scopes: [other] }], me, [other])[1]).toEqual({ scopes: [me] });
    expect(confine('executeUtility', [{}, { scopes: [contract, other] }], me, [other])[1]).toEqual({ scopes: [contract] });
  });

  it('refuses what the protocol does not need', () => {
    expect(confine('getChainInfo', [], me, [])).toEqual([]);
    expect(() => confine('exportSecretKey', [], me, [])).toThrow(NotGranted);
  });
});

describe('samePage', () => {
  it('ties a session to the page and origin that opened it', () => {
    const owner = { tabId: 3, origin: 'https://legit.app' };
    expect(samePage(owner, 3, 'https://legit.app')).toBe(true);
    expect(samePage(owner, 4, 'https://legit.app')).toBe(false); // the next page, after a navigation
    expect(samePage(owner, 3, 'https://evil.app')).toBe(false);
    expect(samePage(undefined, 4, 'https://evil.app')).toBe(true); // a new discovery
  });
});
