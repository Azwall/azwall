import { describe, expect, it } from 'vitest';

import { looksLikeToken } from '../src/discover';

const fns = (...names: string[]) => ({ functions: names.map(name => ({ name })) }) as never;

describe('looksLikeToken', () => {
  it('needs the standard Token functions the wallet calls', () => {
    const token = ['balance_of_private', 'balance_of_public', 'public_get_symbol', 'public_get_decimals', 'transfer'];
    expect(looksLikeToken(fns(...token, 'mint_to_private'))).toBe(true);
    expect(looksLikeToken(fns('swap_exact_tokens_for_tokens', 'add_liquidity'))).toBe(false);
    expect(looksLikeToken(undefined)).toBe(false);
  });
});
