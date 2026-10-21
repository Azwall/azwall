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

// Same schema as the sqlite-opfs worker of the kv-store of the Aztec version this host is built for.
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
/** Under continuous writes the debounce never fires: flush at least this often while anything is pending. */
const FLUSH_MAX_WAIT_MS = 2_000;
/** A failed flush is tried again after this, doubled at each failure in a row, up to a minute. */
const RETRY_FIRST_MS = 1_000;
const RETRY_MAX_MS = 60_000;

/** How long to wait before trying a flush again after `failures` failed flushes in a row. */
export function retryDelay(failures: number): number {
  return Math.min(RETRY_MAX_MS, RETRY_FIRST_MS * 2 ** failures);
}

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
  /** Failed flushes in a row, for the retry backoff. */
  #failures = 0;
  /** Set once a write refused as "not a database" was tried again with a DELETE journal. */
  #triedDeleteJournal = false;
  #closed = false;
  #pendingSince: number | undefined;
  readonly #bornAt = performance.now();

  private constructor(
    readonly disk: AztecSQLiteOPFSStore,
    private readonly mem: any,
    private readonly diskRun: (sql: string, bind?: Bind) => Promise<{ changes: number }>,
    private readonly diskTx: <T>(cb: () => Promise<T>) => Promise<T>,
    private readonly diskClose: () => Promise<void>,
    private readonly diskAll: (sql: string) => Promise<unknown[][]>,
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
    const cache = new CachedStore(disk, mem, d.runAsync.bind(disk), d.transactionAsync.bind(disk), d.close.bind(disk), diskAll, name);

    // Load: one SELECT on the disk database, inserted into memory in one transaction.
    const t0 = performance.now();
    await keepJournal(diskAll, name);
    const rows = await diskAll('SELECT slot, container, key, key_count, hash, value FROM data');
    checkNotReset(name, rows.length);
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
      if (cache.#pending.length) log('store', `${name}: closing with ${cache.stats.pendingStatements} statements not written`, 'error');
      cache.#closed = true;
      cache.mem.close();
      return cache.diskClose();
    };
    d.cache = cache;

    flushWhenHidden(cache, name);

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
    const waited = performance.now() - (this.#pendingSince ??= performance.now());
    this.#timer = setTimeout(() => void this.flush(), Math.max(0, Math.min(FLUSH_DEBOUNCE_MS, FLUSH_MAX_WAIT_MS - waited)));
  }

  /** Writes every committed group to disk in one transaction. Resolves when the disk is caught up. */
  flush(): Promise<void> {
    if (this.#flushing) return this.#flushing.then(() => (this.#pending.length ? this.flush() : undefined));
    if (!this.#pending.length) return Promise.resolve();
    const groups = this.#pending;
    this.#pending = [];
    this.#pendingSince = undefined;
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
        this.#failures = 0;
        rememberRows(this.stats.name, this.rowCount());
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
        const notADatabase = /SQLITE_NOTADB|SQLITE_CORRUPT|file is not a database/.test(this.stats.lastError);
        if (notADatabase && !this.#triedDeleteJournal) return void this.#retryWithDeleteJournal();
        // The files cannot be written with this key (open.ts checks this at open, but not every page): the next
        // start discards them and syncs again rather than failing the same way forever.
        if (notADatabase) markBroken(this.stats.name);
        // Nothing else may commit soon: try again on its own, backing off.
        const delay = retryDelay(this.#failures++);
        clearTimeout(this.#timer);
        this.#timer = setTimeout(() => void this.flush(), delay);
      })
      .finally(() => {
        this.#flushing = null;
      });
    return this.#flushing;
  }

  /**
   * iOS refused every write of fresh, healthy stores as "not a database" while reads and an open-time write worked:
   * the persistent journal is the suspect. Logs what the disk reports, then writes again with a DELETE journal; if
   * that works, this device keeps DELETE (see journalMode).
   */
  async #retryWithDeleteJournal(): Promise<void> {
    this.#triedDeleteJournal = true;
    const name = this.stats.name;
    const ask = (sql: string) => this.diskAll(sql).then(r => JSON.stringify(r).slice(0, 200), e => `error: ${e instanceof Error ? e.message : e}`);
    log('store', `${name}: after the refused write: journal_mode ${await ask('PRAGMA journal_mode')}, quick_check ${await ask('PRAGMA quick_check')}, page_count ${await ask('PRAGMA page_count')}`, 'warn');
    log('store', `${name}: trying again with a DELETE journal: ${await ask('PRAGMA journal_mode = DELETE')}`, 'warn');
    await this.flush();
    if (this.#pending.length) return;
    useDeleteJournal();
    log('store', `${name}: writes work with a DELETE journal; this device keeps it`, 'warn');
  }
}

/**
 * The OPFS pool behind the disk database rewrites a file header whenever a file is created or deleted, and in the
 * default DELETE mode SQLite creates and deletes its rollback journal on every transaction. A process killed during
 * such a write leaves a header the pool cannot validate, and it drops the file. With PERSIST the journal file
 * stays, so a transaction only writes pages, which the journal protects.
 */
async function keepJournal(diskAll: (sql: string) => Promise<unknown[][]>, name: string): Promise<void> {
  const wanted = journalMode();
  const mode = await diskAll(`PRAGMA journal_mode = ${wanted}`).catch(err => [[`error: ${err instanceof Error ? err.message : err}`]]);
  if (String(mode[0]?.[0]).toUpperCase() !== wanted) log('store', `${name}: journal mode is ${String(mode[0]?.[0])}, not ${wanted}`, 'warn');
}

const DELETE_JOURNAL_KEY = 'azwall:journal-delete';

/** PERSIST, unless this device refused writes with it and they worked in DELETE mode (retryWithDeleteJournal). */
export function journalMode(): 'PERSIST' | 'DELETE' {
  try {
    return localStorage.getItem(DELETE_JOURNAL_KEY) === '1' ? 'DELETE' : 'PERSIST';
  } catch {
    return 'PERSIST';
  }
}

export function useDeleteJournal(): void {
  try {
    localStorage.setItem(DELETE_JOURNAL_KEY, '1');
  } catch {
    // without localStorage, the next start tries PERSIST again and falls back again
  }
}

/** Rows the store held at its last flush, per store name: a store found empty after holding data was reset. */
function rowsKey(name: string): string {
  return `azwall:store-rows:${name}`;
}

function rememberRows(name: string, rows: number): void {
  try {
    localStorage.setItem(rowsKey(name), String(rows));
  } catch {
    // diagnostics only (no localStorage in a worker or a private context)
  }
}

function brokenKey(name: string): string {
  return `azwall:store-broken:${name}`;
}

/** Remembers that a store's files refused a write: open.ts discards them at the next start. */
export function markBroken(name: string): void {
  try {
    localStorage.setItem(brokenKey(name), '1');
  } catch {
    // without localStorage, the open-time check still runs
  }
}

export function isMarkedBroken(name: string): boolean {
  try {
    return localStorage.getItem(brokenKey(name)) === '1';
  } catch {
    return false;
  }
}

export function clearBroken(name: string): void {
  try {
    localStorage.removeItem(brokenKey(name));
  } catch {
    // nothing to clear
  }
}

/** Forgets a discarded store's row count, so its empty successor is not reported as a reset. */
export function forgetRows(name: string): void {
  try {
    localStorage.removeItem(rowsKey(name));
  } catch {
    // diagnostics only
  }
}

/** Reports loudly a store that comes back empty after holding data: something wiped or detached it. */
function checkNotReset(name: string, loaded: number): void {
  let before = 0;
  try {
    before = Number(localStorage.getItem(rowsKey(name)) ?? 0);
  } catch {
    return;
  }
  if (loaded === 0 && before > 0) log('store', `${name}: STORE RESET — it held ${before} rows at its last flush and opens empty`, 'error');
  rememberRows(name, loaded);
}

/** Android pauses (then may kill) a hidden web view: write everything pending as soon as the app is hidden. */
function flushWhenHidden(cache: CachedStore, name: string): void {
  // In the PXE worker there is no document: the page forwards visibility changes (lib/remote_wallet.ts).
  if (typeof document === 'undefined') return;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'hidden') return;
    const t = performance.now();
    log('store', `${name}: app hidden, flushing ${cache.stats.pendingStatements} pending statements`);
    void cache.flush().then(() => log('store', `${name}: hidden flush done in ${(performance.now() - t).toFixed(0)} ms`));
  });
  window.addEventListener('pagehide', () => void cache.flush());
}
