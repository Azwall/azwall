import { describe, expect, it, vi } from 'vitest';

import { discoverTokens, looksLikeToken } from '../src/discover';
import { log } from '../src/log';
import { registerTokens } from '../src/token';
import type { AzwallWallet } from '../src/wallet';

vi.mock('../src/log', () => ({ log: vi.fn() }));

const fns = (...names: string[]) => ({ functions: names.map(name => ({ name })) }) as never;
const split = (priv: string[], pub: string[]) =>
  ({ functions: priv.map(name => ({ name })), nonDispatchPublicFunctions: pub.map(name => ({ name })) }) as never;

describe('looksLikeToken', () => {
  it('needs the standard Token functions the wallet calls', () => {
    const token = ['balance_of_private', 'balance_of_public', 'public_get_symbol', 'public_get_decimals', 'transfer'];
    expect(looksLikeToken(fns(...token, 'mint_to_private'))).toBe(true);
    expect(looksLikeToken(fns('swap_exact_tokens_for_tokens', 'add_liquidity'))).toBe(false);
    expect(looksLikeToken(undefined)).toBe(false);
    // As compiled: public functions apart.
    expect(looksLikeToken(split(['balance_of_private', 'transfer'], ['balance_of_public', 'public_get_symbol', 'public_get_decimals']))).toBe(true);
  });
});

describe('tokens that cannot be read', () => {
  it('are left out of discovery, with the reason logged', async () => {
    const wallet = { registeredTokens: async () => ['0xnot-an-address'] } as unknown as AzwallWallet;
    expect(await discoverTokens(wallet, `0x${'01'.repeat(32)}`, [])).toEqual([]);
    expect(log).toHaveBeenCalledWith('tokens', expect.stringContaining('not an Aztec address'), 'warn');
  });

  it('are reported as failed registrations, with the reason logged', async () => {
    const token = `0x${'02'.repeat(32)}`;
    const wallet = { node: { getContract: async () => Promise.reject(new Error('node unreachable')) } } as unknown as AzwallWallet;
    expect(await registerTokens(wallet, [token])).toEqual({ failed: [token] });
    expect(log).toHaveBeenCalledWith('tokens', `registering ${token}: node unreachable`, 'warn');
  });
});
