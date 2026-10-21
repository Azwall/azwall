// The account's private token history, read back from the chain. Aztec keeps no index of an account's
// transactions, but the standard Token emits an encrypted `Transfer { from, to, amount }` event that the PXE
// decrypts with the account's keys, with the hash and block of its transaction. This recovers transfers made
// before the wallet recorded them, or from another wallet holding the same keys.
import { BlockNumber } from '@aztec/aztec.js/fields';
import { TokenContract } from '@aztec/noir-contracts.js/Token';

import { addr } from './token';
import type { AzwallWallet } from './wallet';

export type TokenMove = { token: string; txHash: string; block: number; at: number; from: string; to: string; amount: string };

type Transfer = { from: { toString(): string }; to: { toString(): string }; amount: bigint | number };

export async function tokenHistory(wallet: AzwallWallet, owner: string, tokens: string[]): Promise<TokenMove[]> {
  const scopes = [addr(owner)];
  const perToken = await Promise.all(
    tokens.map(async token => {
      const filter = { contractAddress: addr(token), scopes };
      // A token without the standard event (or not readable) has no history here; the others still do.
      const events = await wallet.getPrivateEvents<Transfer>(TokenContract.events.Transfer, filter).catch(() => []);
      return events.map(e => ({
        token,
        txHash: e.metadata.txHash.toString(),
        block: Number(e.metadata.l2BlockNumber),
        from: e.event.from.toString(),
        to: e.event.to.toString(),
        amount: BigInt(e.event.amount).toString(),
      }));
    }),
  );
  const moves = perToken.flat();
  const times = new Map(await Promise.all([...new Set(moves.map(m => m.block))].map(async b => [b, await blockTime(wallet, b)] as const)));
  return moves.map(m => ({ ...m, at: times.get(m.block) ?? 0 }));
}

/** When the block was built, in milliseconds (0 if the node does not have it). */
async function blockTime(wallet: AzwallWallet, block: number): Promise<number> {
  const response = await wallet.node.getBlock(BlockNumber(block)).catch(() => undefined);
  return response ? Number(response.header.globalVariables.timestamp) * 1000 : 0;
}
