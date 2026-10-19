// Tokens the account uses but the wallet does not follow yet. dApps register the contracts they call in this PXE
// (instance + artifact, kept in its encrypted store), so the tokens a dApp moved for the user are found here
// even when the history of what was done is not: Aztec has no index of an account's private transactions.
import type { ContractArtifact } from '@aztec-labs/stdlib/abi';

import { log } from './log';
import { balances, tokenInfo, type TokenBalance, type TokenInfo } from './token';
import type { AzwallWallet } from './wallet';

export type FoundToken = TokenInfo & Pick<TokenBalance, 'private' | 'public'>;

/** The functions of Aztec's standard Token contract the wallet reads and calls. */
const TOKEN_FUNCTIONS = ['balance_of_private', 'balance_of_public', 'public_get_symbol', 'public_get_decimals', 'transfer'];

export function looksLikeToken(artifact: Pick<ContractArtifact, 'functions' | 'nonDispatchPublicFunctions'> | undefined): boolean {
  // Public functions are listed apart from the private and utility ones (they run through public_dispatch).
  const all = [...(artifact?.functions ?? []), ...(artifact?.nonDispatchPublicFunctions ?? [])];
  const names = new Set(all.map(f => f.name));
  return TOKEN_FUNCTIONS.every(n => names.has(n));
}

/** Registered tokens outside `known`, with the owner's balances; those with nothing for the owner are left out. */
export async function discoverTokens(wallet: AzwallWallet, owner: string, known: string[]): Promise<FoundToken[]> {
  const skip = new Set(known.map(k => k.toLowerCase()));
  const candidates = (await wallet.registeredTokens()).filter(a => !skip.has(a.toLowerCase()));
  const found = await Promise.all(
    candidates.map(async address => {
      try {
        const info = await tokenInfo(wallet, address, owner);
        const [balance] = await balances(wallet, owner, [address]);
        return BigInt(balance.private) + BigInt(balance.public) > 0n ? { ...info, private: balance.private, public: balance.public } : null;
      } catch {
        return null; // not readable as a standard token
      }
    }),
  );
  const held = found.filter(t => t !== null);
  log('tokens', `${candidates.length} registered token(s) not followed, ${held.length} held: ${held.map(t => t.symbol).join(', ') || '-'}`);
  return held;
}
