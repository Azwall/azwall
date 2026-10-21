import { describe, expect, it } from 'vitest';

import { logicalPath } from '../src/pool';

describe('pool headers', () => {
  it('read the logical path an opaque file holds', () => {
    const header = new Uint8Array(512);
    header.set(new TextEncoder().encode('/pxe-1-ab.sqlite3'));
    expect(logicalPath(header)).toBe('/pxe-1-ab.sqlite3');
    expect(logicalPath(new Uint8Array(512))).toBe('');
  });
});
