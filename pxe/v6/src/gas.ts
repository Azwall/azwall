// Gas cache: skips EmbeddedWallet's gas-estimation simulation (a whole extra private execution, ≈ 8 s on a
// Pixel 3) for transaction shapes already seen. The key is sender + called functions; limits come from the
// cache (+25 %), and every proof refreshes the entry with the exact gas the tail kernel computed. A
// gas-related failure drops the entry and retries once the default way. Private-only shapes without
// authwits only. Measured in the prototype: local send time 23.8 s → 17.6 s.
import type { AztecAddress } from '@aztec-labs/aztec.js/addresses';
import type { NO_FROM } from '@aztec-labs/aztec.js/account';
import { NO_WAIT, type InteractionWaitOptions, type SendReturn } from '@aztec-labs/aztec.js/contracts';
import type { ExecutionPayload } from '@aztec-labs/aztec.js/tx';
import type { SendOptions } from '@aztec-labs/aztec.js/wallet';
import { Gas, type GasUsed } from '@aztec-labs/stdlib/gas';
import { TxStatus } from '@aztec-labs/stdlib/tx';
import { BaseWallet, getGasLimits } from '@aztec-labs/wallet-sdk/base-wallet';

import { log } from './log';

const PADDING = 0.25;

type Entry = { totalGas: Gas; teardownGas: Gas };
type Send<W extends InteractionWaitOptions> = (p: ExecutionPayload, o: SendOptions<W>) => Promise<SendReturn<W>>;

/** The protected EmbeddedWallet members the cache needs. */
type WalletInternals = {
  pxe: { sync(): Promise<void>; proveTx: (...a: unknown[]) => Promise<unknown> };
  getMaxTxGasLimits(): Promise<unknown>;
  simulateViaEntrypoint: (...a: unknown[]) => Promise<{ gasUsed?: GasUsed }>;
};

export class GasCache {
  readonly #entries = new Map<string, Entry>();
  #lastSimulated: GasUsed | null = null;
  #keyInFlight: string | null = null;
  readonly stats = { estimated: 0, skipped: 0, retries: 0 };

  key(payload: ExecutionPayload, from: AztecAddress | typeof NO_FROM): string {
    return `${from.toString()}|${payload.calls.map(c => `${c.to.toString()}:${c.selector.toString()}`).join(',')}`;
  }

  /** Records simulated gas and refreshes entries from each proof's tail kernel. */
  instrument(wallet: object): void {
    const w = wallet as unknown as WalletInternals;
    const prove = w.pxe.proveTx.bind(w.pxe);
    w.pxe.proveTx = async (...a: unknown[]) => {
      const result = await prove(...a);
      const pi = (result as { publicInputs?: { forRollup?: unknown; gasUsed?: Gas } }).publicInputs;
      if (this.#keyInFlight && pi?.forRollup && pi.gasUsed) {
        this.#entries.set(this.#keyInFlight, { totalGas: pi.gasUsed, teardownGas: Gas.empty() });
      }
      return result;
    };
    const simulate = w.simulateViaEntrypoint.bind(wallet);
    w.simulateViaEntrypoint = async (...a: unknown[]) => {
      const r = await simulate(...a);
      if (r?.gasUsed) this.#lastSimulated = r.gasUsed;
      return r;
    };
  }

  async send<W extends InteractionWaitOptions>(wallet: object, payload: ExecutionPayload, opts: SendOptions<W>, estimate: Send<W>) {
    const key = this.key(payload, opts.from);
    const cacheable = !opts.fee?.gasSettings?.gasLimits && payload.authWitnesses.length === 0;
    const cached = cacheable ? this.#entries.get(key) : undefined;
    this.#keyInFlight = key;
    try {
      if (cached) {
        const sent = await this.#sendKnown(wallet, payload, opts, cached).catch((err: unknown) => {
          const msg = err instanceof Error ? err.message : String(err);
          if (!/gas/i.test(msg)) throw err;
          this.#entries.delete(key);
          this.stats.retries++;
          log('gas', `cached gas limits rejected (${msg.slice(0, 120)}); re-estimating`, 'warn');
          return null;
        });
        if (sent) {
          this.stats.skipped++;
          return sent;
        }
      }
      return await this.#estimateAndSend(payload, opts, key, estimate);
    } finally {
      this.#keyInFlight = null;
    }
  }

  async #estimateAndSend<W extends InteractionWaitOptions>(payload: ExecutionPayload, opts: SendOptions<W>, key: string, estimate: Send<W>) {
    const authwitsBefore = payload.authWitnesses.length;
    this.#lastSimulated = null;
    const result = await estimate(payload, opts);
    this.stats.estimated++;
    const g = this.#lastSimulated as GasUsed | null;
    if (g && payload.authWitnesses.length === authwitsBefore && g.publicGas.isEmpty() && !this.#entries.has(key)) {
      this.#entries.set(key, { totalGas: g.totalGas, teardownGas: g.teardownGas });
    }
    return result;
  }

  /** BaseWallet.sendTx with explicit limits: one private execution, no estimation pass. */
  async #sendKnown<W extends InteractionWaitOptions>(wallet: object, payload: ExecutionPayload, opts: SendOptions<W>, cached: Entry) {
    const w = wallet as unknown as WalletInternals;
    await w.pxe.sync(); // the embedded PXE runs with autoSync off; EmbeddedWallet.sendTx syncs here too
    const max = await w.getMaxTxGasLimits();
    const used: GasUsed = { totalGas: cached.totalGas, teardownGas: cached.teardownGas, publicGas: Gas.empty(), billedGas: cached.totalGas };
    const limits = getGasLimits(used, max as never, PADDING);
    const wait = opts.wait === NO_WAIT ? opts.wait : ({ ...(typeof opts.wait === 'object' ? opts.wait : {}), waitForStatus: TxStatus.PROPOSED } as W);
    const fee = { ...opts.fee, gasSettings: { ...opts.fee?.gasSettings, ...limits } };
    return BaseWallet.prototype.sendTx.call(wallet as BaseWallet, payload, { ...opts, wait, fee }) as Promise<SendReturn<W>>;
  }
}
