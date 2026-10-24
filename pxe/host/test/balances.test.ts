import { describe, expect, it, vi } from 'vitest';

// A token contract whose balances read 7 and 3, except for the token `missing`, which no node knows.
const missing = `0x${'0f'.repeat(32)}`;
vi.mock('@aztec/noir-contracts.js/Token', () => ({
  TokenContractArtifact: {},
  TokenContract: {
    at: (address: { toString(): string }) => {
      const read = (value: bigint) => () => ({
        simulate: async () => {
          if (address.toString() === missing) throw new Error('its instance is not registered nor published');
          return { result: value };
        },
      });
      return { methods: { balance_of_private: read(7n), balance_of_public: read(3n) } };
    },
  },
}));

const { balances } = await import('../src/token');

describe('balances', () => {
  it('leave out a token the network does not know, and read the others', async () => {
    const wallet = { node: { getContract: async () => undefined } } as never;
    const known = `0x${'01'.repeat(32)}`;
    const owner = `0x${'02'.repeat(32)}`;
    expect(await balances(wallet, owner, [missing, known])).toEqual([{ token: known, private: '7', public: '3' }]);
  });
});
