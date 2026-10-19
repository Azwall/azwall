import { describe, expect, it } from 'vitest';

import { outcomeOf } from '../src/wallet';

describe('outcomeOf', () => {
  it('folds node statuses into what the app shows', () => {
    expect(outcomeOf({ status: 'pending' })).toBe('pending');
    expect(outcomeOf({ status: 'dropped' })).toBe('dropped');
    expect(outcomeOf({ status: 'proposed', executionResult: 'success' })).toBe('success');
    expect(outcomeOf({ status: 'finalized', executionResult: 'reverted' })).toBe('reverted');
  });
});
