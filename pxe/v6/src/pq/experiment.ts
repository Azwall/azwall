// Measures what a post-quantum account costs on this device, against the Schnorr account the wallet uses: key
// generation, then the same transaction (a token deployment) sent from each account, on the open network. Every
// step is logged ([pq], [prover]); fees are paid by the Sponsored FPC. Run it from a debugger: it is not exposed in
// the app.
import { NO_FROM } from '@aztec-labs/aztec.js/account';
import { NO_WAIT } from '@aztec-labs/aztec.js/contracts';
import { SponsoredFeePaymentMethod } from '@aztec-labs/aztec.js/fee';
import { Fq, Fr } from '@aztec-labs/aztec.js/fields';
import type { AccountManager } from '@aztec-labs/aztec.js/wallet';

import { log } from '../log';
import { deployToken } from '../token';
import { type AzwallWallet, sponsoredFpc } from '../wallet';
import { buildTree } from './wots';

const DEPTH = 10;

async function timed<T>(label: string, run: () => Promise<T>): Promise<[T, number]> {
  const t0 = performance.now();
  const value = await run();
  const ms = Math.round(performance.now() - t0);
  log('pq', `${label}: ${ms} ms`);
  return [value, ms];
}

/** Deploys an account (fee sponsored) and waits for its block. */
async function deploy(wallet: AzwallWallet, manager: AccountManager, label: string): Promise<number> {
  const method = await manager.getDeployMethod();
  const fpc = await sponsoredFpc();
  const fee = { paymentMethod: new SponsoredFeePaymentMethod(fpc.instance.address) };
  const [sent, ms] = await timed(`${label} account deployment proven and sent`, () => method.send({ from: NO_FROM, fee, wait: NO_WAIT }));
  await wallet.waitForReceipt(sent.txHash.toString());
  return ms;
}

/** Sends the same transaction from `account` (its entrypoint verifies the signature) and waits for its block. */
async function tokenFrom(wallet: AzwallWallet, account: string, label: string): Promise<number> {
  const [sent, ms] = await timed(`${label} token deployment proven and sent`, () => deployToken(wallet, account, 'PQ test', 'PQT', 6));
  await wallet.waitForReceipt(sent.txHash);
  return ms;
}

export async function pqExperiment(wallet: AzwallWallet) {
  const seed = Fr.random();
  log('pq', `building ${2 ** DEPTH} one-time keys`);
  const [levels, keygenMs] = await timed('one-time keys and Merkle tree', () =>
    buildTree(seed, DEPTH, n => n % 128 === 0 && log('pq', `${n} keys`)),
  );
  const schnorr = await wallet.addAccount({ alias: 'pq-baseline', signingKey: Fq.random().toString(), salt: Fr.ZERO.toString() });
  const pq = await wallet.addPqAccount(seed, levels);
  log('pq', `accounts: schnorr ${schnorr.address}, pq ${pq.address}`);
  const result = {
    keygenMs,
    schnorrDeployMs: await deploy(wallet, schnorr, 'Schnorr'),
    pqDeployMs: await deploy(wallet, pq, 'PQ'),
    schnorrTxMs: await tokenFrom(wallet, schnorr.address.toString(), 'Schnorr'),
    pqTxMs: await tokenFrom(wallet, pq.address.toString(), 'PQ'),
  };
  log('pq', `RESULT ${JSON.stringify(result)}`);
  return result;
}
