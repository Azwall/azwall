// iOS keeps the PXE stores in the app's own files instead of OPFS: WebKit took the OPFS pool away from the hidden PXE
// page while it was open (the pool's directory vanished between two writes), so every write failed and each start
// synced again from nothing. The stores run in memory (store.ts); the app is handed a sealed copy of each database
// (gzip, then AES-GCM with the stores' key: it holds no secret it could read) and gives it back at the next start.
import { Buffer } from 'buffer';
import { openTmpStore } from '@aztec/kv-store/sqlite-opfs';
import { type Bridge, errorMessage } from '@azwall/pxe-shared';

import { log } from './log';
import { CachedStore } from './store';

const IV_BYTES = 12;

/** True when the app keeps the stores (it says so before the page loads). */
export function appKeepsStores(): boolean {
  return (globalThis as { azwallNativeStore?: unknown }).azwallNativeStore === true;
}

function aesKey(key: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', key as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

/** `iv ‖ AES-GCM(plain)`. */
export async function seal(key: Uint8Array, plain: Uint8Array): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aesKey(key), plain as BufferSource));
  const out = new Uint8Array(IV_BYTES + sealed.byteLength);
  out.set(iv);
  out.set(sealed, IV_BYTES);
  return out;
}

export async function unseal(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const iv = data.subarray(0, IV_BYTES);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv as BufferSource }, await aesKey(key), data.subarray(IV_BYTES) as BufferSource));
}

/** Runs `bytes` through a (de)compression stream: contract artifacts make up most of a store, and shrink well. */
async function through(bytes: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const out = new Blob([bytes as BlobPart]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(out).arrayBuffer());
}

/** The app's sealed copies, by store name (`storeLoad` / `storeSave` in the app). */
export class AppCopies {
  constructor(
    private readonly bridge: Bridge,
    private readonly key: Uint8Array,
  ) {}

  /** The copy to start from; none when there is none yet, or when this wallet's key cannot open it. */
  async load(name: string): Promise<Uint8Array | null> {
    const { data } = await this.bridge.callNative<{ data: string | null }>('storeLoad', { name });
    if (!data) return null;
    try {
      return await through(await unseal(this.key, Buffer.from(data, 'base64')), new DecompressionStream('gzip'));
    } catch (err) {
      // Another wallet's key, or a damaged file: the next save replaces it, so it is set aside first, never lost.
      await this.bridge.callNative('storeSave', { name: `${name}-unreadable`, data });
      log('store', `${name}: the app's copy cannot be opened (${errorMessage(err)}): set aside, starting empty`, 'warn');
      return null;
    }
  }

  async save(name: string, bytes: Uint8Array): Promise<void> {
    const data = Buffer.from(await seal(this.key, await through(bytes, new CompressionStream('gzip')))).toString('base64');
    await this.bridge.callNative('storeSave', { name, data });
  }
}

/** Opens both stores in memory, from the app's copies. The kv-store needs a store object: an ephemeral one. */
export async function openAppStores(names: [string, string], copies: AppCopies) {
  const [pxeStore, walletStore] = await Promise.all([openTmpStore(true), openTmpStore(true)]);
  const caches: CachedStore[] = [];
  for (const [i, store] of [pxeStore, walletStore].entries()) {
    const name = names[i]!;
    const snapshot = await copies.load(name);
    caches.push(await CachedStore.wrap(store, name, { snapshot, save: bytes => copies.save(name, bytes) }));
  }
  return { pxeStore, walletStore, caches };
}
