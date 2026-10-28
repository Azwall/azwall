// Token operations on Aztec's standard Token contract. Balances have a private part (notes only the owner
// can see) and a public part (in public storage); transfers move value inside or between the two.
import { AztecAddress } from '@aztec/aztec.js/addresses';
import { NO_WAIT } from '@aztec/aztec.js/contracts';
import { Fr } from '@aztec/aztec.js/fields';
import { readFieldCompressedString } from '@aztec/aztec.js/utils';
import { TokenContract, TokenContractArtifact } from '@aztec/noir-contracts.js/Token';
import { errorMessage } from '@azwall/pxe-shared';

import { log } from './log';
import type { AzwallWallet } from './wallet';

export type TokenInfo = { address: string; name: string; symbol: string; decimals: number };
export type TokenBalance = { token: string; private: string; public: string };
export type TransferKind = 'private' | 'public' | 'shield' | 'unshield';

/** Parses a 0x-prefixed, 32-byte address; the stdlib parser does not validate the format. */
export function addr(s: string): AztecAddress {
  if (!/^0x[0-9a-fA-F]{64}$/.test(s)) throw new Error(`not an Aztec address: ${s}`);
  return AztecAddress.fromStringUnsafe(s);
}

/**
 * Throws unless `to` can receive `token`: an address must be a point of the Grumpkin curve to be encrypted to (about
 * half of all mistyped addresses are not, and notes sent there can never be spent), and the zero address or the
 * token contract itself would only burn what is sent.
 */
export async function checkRecipient(to: AztecAddress, token: AztecAddress): Promise<void> {
  if (to.isZero()) throw new Error('the recipient is the zero address: what is sent there is lost');
  if (to.equals(token)) throw new Error('the recipient is the token contract itself: what is sent there is lost');
  if (!(await to.isValid())) throw new Error('this is not a valid Aztec address (check it for a typo): what is sent there could never be spent');
}

/** Makes a token deployed elsewhere known to this PXE (instance + artifact). */
export async function registerToken(wallet: AzwallWallet, token: string): Promise<TokenContract> {
  const address = addr(token);
  const instance = await wallet.node.getContract(address);
  if (!instance) throw new Error(`no contract at ${token} on this network`);
  // A token a dApp registered keeps its own artifact (its class may differ from this package's Token).
  const { isArtifactRegistered } = await wallet.getContractClassMetadata(instance.currentContractClassId);
  await wallet.registerContract(instance, isArtifactRegistered ? undefined : TokenContractArtifact);
  return TokenContract.at(address, wallet);
}

/**
 * The PXE store can start over (a pool damaged by a crash is set aside): the tokens the app follows are registered
 * again on opening. A token that cannot be registered is reported, not fatal.
 */
export async function registerTokens(wallet: AzwallWallet, tokens: string[]): Promise<{ failed: string[] }> {
  const failed: string[] = [];
  for (const token of tokens) {
    await registerToken(wallet, token).catch(err => {
      log('tokens', `registering ${token}: ${err instanceof Error ? err.message : String(err)}`, 'warn');
      failed.push(token);
    });
  }
  return { failed };
}

function decodeString(result: unknown): string {
  const value = (result as { value?: bigint }).value ?? result;
  return readFieldCompressedString({ value: BigInt(value as bigint) });
}

export async function tokenInfo(wallet: AzwallWallet, token: string, from: string): Promise<TokenInfo> {
  const contract = await registerToken(wallet, token);
  const opts = { from: addr(from) };
  const [name, symbol, decimals] = await Promise.all([
    contract.methods.public_get_name().simulate(opts),
    contract.methods.public_get_symbol().simulate(opts),
    contract.methods.public_get_decimals().simulate(opts),
  ]);
  return { address: contract.address.toString(), name: decodeString(name.result), symbol: decodeString(symbol.result), decimals: Number(decimals.result) };
}

/**
 * The balances of `tokens` the PXE can read. A token it cannot (not on this chain: a followed token from before a
 * network reset; or not registered yet) is registered and read again once, then left out: one such token must not
 * hide every other balance, and a refresh that always failed left the wallet showing last session's numbers.
 */
const unreadable = new Set<string>();

export async function balances(wallet: AzwallWallet, owner: string, tokens: string[]): Promise<TokenBalance[]> {
  const me = addr(owner);
  const read = async (token: string) => {
    const contract = TokenContract.at(addr(token), wallet);
    const [priv, pub] = await Promise.all([
      contract.methods.balance_of_private(me).simulate({ from: me }),
      contract.methods.balance_of_public(me).simulate({ from: me }),
    ]);
    return { token, private: BigInt(priv.result).toString(), public: BigInt(pub.result).toString() };
  };
  const results = await Promise.all(
    tokens.map(token =>
      read(token).catch(async () => {
        await registerToken(wallet, token);
        return read(token);
      }).catch(err => {
        // Once per token: a refresh every 20 s would repeat it.
        if (!unreadable.has(token)) log('tokens', `${token}: no balance, left out (${errorMessage(err)})`, 'warn');
        unreadable.add(token);
        return undefined;
      }),
    ),
  );
  return results.filter((b): b is TokenBalance => b !== undefined);
}

/**
 * Sends a transfer and returns its hash once proven and accepted by the node (mining is awaited separately).
 * private: notes to notes · public: public to public · shield: own public to own private · unshield: the reverse.
 */
export async function transfer(wallet: AzwallWallet, kind: TransferKind, from: string, to: string, token: string, amount: bigint) {
  const sender = addr(from);
  const recipient = addr(to);
  await checkRecipient(recipient, addr(token));
  const m = TokenContract.at(addr(token), wallet).methods;
  const call = {
    private: () => m.transfer(recipient, amount),
    public: () => m.transfer_in_public(sender, recipient, amount, Fr.ZERO),
    shield: () => m.transfer_to_private(recipient, amount),
    unshield: () => m.transfer_to_public(sender, recipient, amount, Fr.ZERO),
  }[kind]();
  const { txHash } = await call.send({ from: sender, wait: NO_WAIT });
  return txHash.toString();
}

/** Deploys a fresh token with `admin` as minter (test networks). */
export async function deployToken(wallet: AzwallWallet, admin: string, name: string, symbol: string, decimals: number) {
  const me = addr(admin);
  const deploy = TokenContract.deploy(wallet, me, name, symbol, decimals, { deployer: me });
  const address = (await deploy.getInstance()).address;
  const { txHash } = await deploy.send({ from: me, wait: NO_WAIT });
  return { token: address.toString(), txHash: txHash.toString() };
}

export async function mint(wallet: AzwallWallet, admin: string, token: string, to: string, amount: bigint, toPrivate: boolean) {
  await checkRecipient(addr(to), addr(token));
  const m = TokenContract.at(addr(token), wallet).methods;
  const call = toPrivate ? m.mint_to_private(addr(to), amount) : m.mint_to_public(addr(to), amount);
  const { txHash } = await call.send({ from: addr(admin), wait: NO_WAIT });
  return txHash.toString();
}
