// Builds the PXE host into assets/pxe/v6/, which both apps bundle and load in a hidden web view.
import { fileURLToPath } from 'node:url';

import { defineConfig, type Plugin } from 'vite';
import { nodePolyfills, type PolyfillOptions } from 'vite-plugin-node-polyfills';

// Workaround for https://github.com/davidmyersdev/vite-plugin-node-polyfills/issues/81: resolve the shims.
const nodePolyfillsFix = (options?: PolyfillOptions): Plugin[] => [
  ...nodePolyfills(options),
  {
    name: 'azwall:polyfill-shims',
    resolveId(source: string) {
      const m = /^vite-plugin-node-polyfills\/shims\/(buffer|global|process)$/.exec(source);
      if (m) return fileURLToPath(new URL(`./node_modules/vite-plugin-node-polyfills/shims/${m[1]}/dist/index.cjs`, import.meta.url));
    },
  },
];

// Under Vite 8 (rolldown) the polyfill plugin's inject step does not run; rolldown's own inject does.
const inject = { Buffer: ['buffer', 'Buffer'] as [string, string], process: 'process' };
const polyfills = () => nodePolyfillsFix({ globals: { process: true, Buffer: true, global: true } });

export default defineConfig({
  base: './',
  plugins: polyfills(),
  build: {
    outDir: fileURLToPath(new URL('../../assets/pxe/v6', import.meta.url)),
    emptyOutDir: true,
    target: 'esnext',
    chunkSizeWarningLimit: 20_000,
    rolldownOptions: { transform: { inject } },
  },
  worker: { format: 'es', plugins: polyfills, rolldownOptions: { transform: { inject } } },
  optimizeDeps: {
    include: ['pino', 'pino/browser'],
    exclude: [
      '@aztec-foundation/noir-noirc_abi',
      '@aztec-foundation/noir-acvm_js',
      '@aztec-foundation/bb.js',
      '@aztec-foundation/noir-noir_js',
      '@aztec-labs/kv-store',
    ],
  },
});
