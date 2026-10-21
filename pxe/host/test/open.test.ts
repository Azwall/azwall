import { EmbeddedWalletEncryptionError } from '@aztec/wallets/embedded/store-encryption';
import { describe, expect, it } from 'vitest';

import { startOverOnOtherKey } from '../src/open';

const otherKey = () => new EmbeddedWalletEncryptionError('pxe', { cause: new Error('bad key') as never });

describe('startOverOnOtherKey', () => {
  it("discards stores another wallet's key encrypted, then opens fresh ones", async () => {
    const calls: string[] = [];
    let first = true;
    const opened = await startOverOnOtherKey(
      async () => {
        calls.push('open');
        if (first) {
          first = false;
          throw otherKey();
        }
        return 'stores';
      },
      async () => {
        calls.push('discard');
      },
    );
    expect(opened).toBe('stores');
    expect(calls).toEqual(['open', 'discard', 'open']);
  });

  it('never discards on other errors', async () => {
    let discarded = false;
    const failing = startOverOnOtherKey(
      async () => {
        throw new Error('disk full');
      },
      async () => {
        discarded = true;
      },
    );
    await expect(failing).rejects.toThrow('disk full');
    expect(discarded).toBe(false);
  });
});
