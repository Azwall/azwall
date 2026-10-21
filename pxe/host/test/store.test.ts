import { describe, expect, it } from 'vitest';

import { retryDelay } from '../src/store';

describe('store', () => {
  it('retries a failed flush sooner first, then backs off to a minute', () => {
    expect([0, 1, 2, 5, 6, 20].map(retryDelay)).toEqual([1_000, 2_000, 4_000, 32_000, 60_000, 60_000]);
  });
});
