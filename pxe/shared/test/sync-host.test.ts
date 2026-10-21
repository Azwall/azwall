import { describe, expect, it } from 'vitest';

import { rename } from '../../scripts/sync-host.mjs';

const v6: [string, string][] = [
  ['@aztec/bb.js', '@aztec-foundation/bb.js'],
  ['@aztec/', '@aztec-labs/'],
];

describe('sync-host', () => {
  it("renames pxe/host's imports to a version's packages, first matching rule", () => {
    const source = [
      "import { Fr } from '@aztec/aztec.js/fields';",
      "import { BarretenbergSync } from '@aztec/bb.js';",
      "export { x } from '@aztec/stdlib/abi';",
      "const m = await import('@aztec/pxe/client');",
      "import { log } from './log';",
      "// a comment about '@aztec/kv-store' stays",
    ].join('\n');
    expect(rename(source, v6).split('\n')).toEqual([
      "import { Fr } from '@aztec-labs/aztec.js/fields';",
      "import { BarretenbergSync } from '@aztec-foundation/bb.js';",
      "export { x } from '@aztec-labs/stdlib/abi';",
      "const m = await import('@aztec-labs/pxe/client');",
      "import { log } from './log';",
      "// a comment about '@aztec/kv-store' stays",
    ]);
    expect(rename(source, [])).toBe(source);
  });
});
