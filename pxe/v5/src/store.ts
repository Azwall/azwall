// Memory-first front for the PXE's encrypted SQLite-OPFS store.
//
// Why: AztecSQLiteOPFSStore runs SQLite in a worker and sends one message per operation. A transfer that
// has to sync a new block makes ~2 500 of them; on a phone-class CPU that is ~10 s on the critical path.
//
// How: the store's containers (map, multimap, set, array, singleton) only talk to it through
// allAsync / runAsync / transactionAsync. We intercept those three on the opened store instance and run
// the *same SQL* against an in-memory SQLite on the main thread, loaded once from the disk database at
// startup (a single SELECT). Writes are recorded, per committed transaction, in an ordered journal and
// replayed onto the encrypted disk database in the background.
//
// Durability: a flush replays every pending group inside ONE disk transaction, so the database on disk
// is always a prefix of the in-memory history (the PXE re-syncs whatever blocks it lost). Flushes run
// shortly after each commit (debounced), when the page is hidden (Android pauses/freezes the WebView
// right after), on pagehide, and before close. A flush failure is kept for retry and reported loudly.
import sqlite3InitModule from '@aztec/sqlite3mc-wasm';
import type { AztecSQLiteOPFSStore } from '@aztec/kv-store/sqlite-opfs';

import { log } from './log';

// Same schema as @aztec/kv-store's sqlite-opfs worker (5.1.0).
const SCHEMA_SQL = `
  CREATE TABLE IF NOT EXISTS data (
    slot TEXT NOT NULL PRIMARY KEY,
    container TEXT NOT NULL,
    key BLOB NOT NULL,
    key_count INTEGER NOT NULL,
    hash TEXT NOT NULL,
    value BLOB
  ) WITHOUT ROWID;
  CREATE INDEX IF NOT EXISTS idx_container_key ON data(container, key);
  CREATE UNIQUE INDEX IF NOT EXISTS idx_container_key_count ON data(container, key, key_count);
  CREATE UNIQUE INDEX IF NOT EXISTS idx_container_key_hash ON data(container, key, hash);
`;

const FLUSH_DEBOUNCE_MS = 250;

type Stmt = { sql: string; bind: unknown[] | undefined };
type Bind = unknown[] | undefined;

export type CacheStats = {
  name: string;
  loadedRows: number;
  loadMs: number;
  pendingGroups: number;
  pendingStatements: number;
  flushes: number;
  lastFlushMs: number;
  lastFlushStatements: number;
  lastFlushBytes: number;
  /** Largest statements of the last flush: [container prefix of the slot, bytes]. */
  lastFlushLargest: [string, number][];
  /** Every flush: [ms since load, statements, bytes, duration ms]. */
  history: [number, number, number, number][];
  flushErrors: number;
  lastError: string | null;
};

let sqlitePromise: ReturnType<typeof sqlite3InitModule> | undefined;

export class CachedStore {
  readonly stats: CacheStats;
  #pending: Stmt[][] = [];
  #group: Stmt[] | null = null;
  #txChain: Promise<unknown> = Promise.resolve();
  #flushing: Promise<void> | null = null;
  #timer: ReturnType<typeof setTimeout> | undefined;
  #closed = false;
  readonly #bornAt = performance.now();

  private constructor(
    readonly disk: AztecSQLiteOPFSStore,
    private readonly mem: any,
    private readonly diskRun: (sql: string, bind?: Bind) => Promise<{ changes: number }>,
    private readonly diskTx: <T>(cb: () => Promise<T>) => Promise<T>,
    private readonly diskClose: () => Promise<void>,
    name: string,
  ) {
    this.stats = {
      name,
      loadedRows: 0,
      loadMs: 0,
      pendingGroups: 0,
      pendingStatements: 0,
      flushes: 0,
      lastFlushMs: 0,
      lastFlushStatements: 0,
      lastFlushBytes: 0,
      lastFlushLargest: [],
      history: [],
      flushErrors: 0,
      lastError: null,
    };
  }

  /** Wraps an opened store in place and returns the controller. The store object keeps its identity. */
  static async wrap(disk: AztecSQLiteOPFSStore, name: string): Promise<CachedStore> {
    const sqlite = await (sqlitePromise ??= sqlite3InitModule());
    const mem = new sqlite.oo1.DB(':memory:', 'c');
    mem.exec(SCHEMA_SQL);

    const d = disk as any;
    const diskAll = d.allAsync.bind(disk) as (sql: string, bind?: Bind) => Promise<unknown[][]>;
    const cache = new CachedStore(disk, mem, d.runAsync.bind(disk), d.transactionAsync.bind(disk), d.close.bind(disk), name);

    // Load: one SELECT on the disk database, inserted into memory in one transaction.
    const t0 = performance.now();
    const rows = await diskAll('SELECT slot, container, key, key_count, hash, value FROM data');
    mem.exec('BEGIN');
    const ins = mem.prepare('INSERT INTO data (slot, container, key, key_count, hash, value) VALUES (?, ?, ?, ?, ?, ?)');
    try {
      for (const r of rows) ins.bind(r).stepReset();
    } finally {
      ins.finalize();
    }
    mem.exec('COMMIT');
    cache.stats.loadedRows = rows.length;
    cache.stats.loadMs = performance.now() - t0;

    d.allAsync = (sql: string, bind?: Bind) => Promise.resolve(cache.#all(sql, bind));
    d.runAsync = (sql: string, bind?: Bind) => Promise.resolve(cache.#run(sql, bind));
    d.transactionAsync = <T>(cb: () => Promise<T>) => cache.#transaction(cb);
    d.close = async () => {
      await cache.flush();
      cache.#closed = true;
      cache.mem.close();
      return cache.diskClose();
    };
    d.cache = cache;

    const onHidden = () => {
      if (document.visibilityState !== 'hidden') return;
      const n = cache.stats.pendingStatements;
      const t = performance.now();
      log('store', `${name}: app hidden, flushing ${n} pending statements`);
      void cache.flush().then(() => log('store', `${name}: hidden flush done in ${(performance.now() - t).toFixed(0)} ms`));
    };
    // In the PXE worker there is no document: the page forwards visibility changes (lib/remote_wallet.ts).
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onHidden);
      window.addEventListener('pagehide', () => void cache.flush());
    }

    log('store', `${name}: ${rows.length} rows loaded into memory in ${cache.stats.loadMs.toFixed(0)} ms`);
    return cache;
  }

  /** Rows currently in the in-memory database (tests and diagnostics). */
  rowCount(): number {
    return Number(this.#all('SELECT COUNT(*) FROM data', undefined)[0]?.[0] ?? 0);
  }

  #all(sql: string, bind: Bind): unknown[][] {
    const rows: unknown[][] = [];
    this.mem.exec({ sql, bind, rowMode: 'array', resultRows: rows });
    return rows;
  }

  #run(sql: string, bind: Bind): { changes: number } {
    if (this.#closed) throw new Error(`${this.stats.name}: store is closed`);
    this.mem.exec({ sql, bind });
    const changes = this.mem.changes();
    // Copy the bind values into exactly-sized buffers. The store's msgpack encoder returns Buffers that are
    // views into a large shared buffer, and Buffer#slice is a view too: posting such a view to the SQLite
    // worker structured-clones the WHOLE underlying buffer (MBs) per statement. new Uint8Array(view) copies
    // just the bytes. (The journal also outlives the caller's arrays.)
    const stmt = { sql, bind: bind ? bind.map(v => (v instanceof Uint8Array ? new Uint8Array(v) : v)) : undefined };
    if (this.#group) this.#group.push(stmt);
    else this.#commitGroup([stmt]);
    return { changes };
  }

  /**
   * Same semantics as AztecSQLiteOPFSStore.transactionAsync: transactions are serialised, and any
   * operation issued while one is open joins it (nested calls just run the callback).
   */
  #transaction<T>(cb: () => Promise<T>): Promise<T> {
    if (this.#group) return cb();
    const run = async () => {
      this.mem.exec('BEGIN');
      this.#group = [];
      try {
        const result = await cb();
        this.mem.exec('COMMIT');
        const group = this.#group;
        this.#group = null;
        if (group.length) this.#commitGroup(group);
        return result;
      } catch (err) {
        this.mem.exec('ROLLBACK');
        this.#group = null;
        throw err;
      }
    };
    const p = this.#txChain.then(run, run);
    this.#txChain = p.catch(() => {});
    return p;
  }

  #commitGroup(group: Stmt[]) {
    this.#pending.push(group);
    this.stats.pendingGroups = this.#pending.length;
    this.stats.pendingStatements += group.length;
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => void this.flush(), FLUSH_DEBOUNCE_MS);
  }

  /** Writes every committed group to disk in one transaction. Resolves when the disk is caught up. */
  flush(): Promise<void> {
    if (this.#flushing) return this.#flushing.then(() => (this.#pending.length ? this.flush() : undefined));
    if (!this.#pending.length) return Promise.resolve();
    const groups = this.#pending;
    this.#pending = [];
    this.stats.pendingGroups = 0;
    this.stats.pendingStatements = 0;
    const count = groups.reduce((a, g) => a + g.length, 0);
    const sizes: [string, number][] = groups.flat().map(st => [
      String(st.bind?.[1] ?? st.sql.slice(0, 30)),
      (st.bind ?? []).reduce((a: number, v) => a + (v instanceof Uint8Array ? v.byteLength : String(v ?? '').length), 0),
    ]);
    this.stats.lastFlushBytes = sizes.reduce((a, x) => a + x[1], 0);
    this.stats.lastFlushLargest = [...sizes].sort((a, b) => b[1] - a[1]).slice(0, 5);
    const t0 = performance.now();
    this.#flushing = this.diskTx(async () => {
      for (const g of groups) for (const s of g) await this.diskRun(s.sql, s.bind);
    })
      .then(() => {
        this.stats.flushes++;
        this.stats.lastFlushMs = performance.now() - t0;
        this.stats.lastFlushStatements = count;
        this.stats.history.push([Math.round(t0 - this.#bornAt), count, this.stats.lastFlushBytes, Math.round(this.stats.lastFlushMs)]);
      })
      .catch(err => {
        // Keep the groups, in order, ahead of anything committed meanwhile; nothing is lost from memory.
        this.#pending = [...groups, ...this.#pending];
        this.stats.pendingGroups = this.#pending.length;
        this.stats.pendingStatements += count;
        this.stats.flushErrors++;
        this.stats.lastError = err instanceof Error ? err.message : String(err);
        log('store', `${this.stats.name}: flush of ${count} statements FAILED (kept for retry): ${this.stats.lastError}`, 'error');
      })
      .finally(() => {
        this.#flushing = null;
      });
    return this.#flushing;
  }
}
