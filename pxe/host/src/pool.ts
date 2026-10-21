// Read-only look at a store's OPFS pool, for the logs when its database turns unreadable: the pool keeps each SQLite
// file in an opaque file whose first 512 bytes name the logical file it holds. Two opaque files naming the same one
// mean two writers used the pool at once.
import { storePoolDirectory } from '@aztec/kv-store/sqlite-opfs';

const HEADER_PATH_BYTES = 512;

/** "name (size): logical path" for every opaque file of the store's pool, or why it could not be read. */
export async function describePool(name: string): Promise<string> {
  try {
    let dir = await navigator.storage.getDirectory();
    for (const part of storePoolDirectory(name).split('/').filter(Boolean)) dir = await dir.getDirectoryHandle(part);
    const opaque = await dir.getDirectoryHandle('.opaque');
    const lines: string[] = [];
    for await (const [file, handle] of (opaque as unknown as AsyncIterable<[string, FileSystemHandle]>)) {
      if (handle.kind !== 'file') continue;
      lines.push(`${file} (${await describeFile(handle as FileSystemFileHandle)})`);
    }
    return lines.join('; ') || 'empty';
  } catch (err) {
    return `unreadable: ${err instanceof Error ? err.message : err}`;
  }
}

async function describeFile(handle: FileSystemFileHandle): Promise<string> {
  try {
    const file = await handle.getFile();
    return `${file.size} B: ${logicalPath(new Uint8Array(await file.slice(0, HEADER_PATH_BYTES).arrayBuffer())) || 'unused'}`;
  } catch (err) {
    return `unreadable: ${err instanceof Error ? err.message : err}`;
  }
}

/** The NUL-terminated path at the start of an opaque file's header. */
export function logicalPath(header: Uint8Array): string {
  const end = header.indexOf(0);
  return new TextDecoder().decode(end < 0 ? header : header.subarray(0, end));
}
