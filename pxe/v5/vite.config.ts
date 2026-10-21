// Builds the PXE host into assets/pxe/v5/, which both apps bundle and load in a hidden web view.
import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
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
// @aztec/sqlite3mc-wasm 5.1.0 fetches `sqlite3.wasm` next to its script, under that exact name (6.x passes a
// bundler-visible URL instead). Without this copy the fetch 404s and the store init never settles.
const sqliteWasm = (): Plugin => ({
  name: 'azwall:sqlite3-wasm',
  generateBundle() {
    const wasm = new URL('./node_modules/@aztec/sqlite3mc-wasm/vendor/jswasm/sqlite3.wasm', import.meta.url);
    this.emitFile({ type: 'asset', fileName: 'assets/sqlite3.wasm', source: readFileSync(wasm) });
  },
});

const polyfills = () => nodePolyfillsFix({ globals: { process: true, Buffer: true, global: true } });

const root = (path: string) => fileURLToPath(new URL(`../../${path}`, import.meta.url));

/**
 * `0.1.0+1a2b3c4` (`-dirty` with uncommitted changes): the app version (Cargo workspace) and the commit, logged when
 * the host loads so a log always says which build produced it. Without git (a copy synced to a build machine), the
 * commit comes from .azwall-commit, written by whoever copied the tree.
 */
function buildId(): string {
  const version = /\[workspace\.package\][^[]*?version\s*=\s*"([^"]+)"/.exec(readFileSync(root('Cargo.toml'), 'utf8'))?.[1] ?? '0.0.0';
  let commit = 'unknown';
  try {
    const dirty = execSync('git status --porcelain', { cwd: root('.') }).toString().trim() ? '-dirty' : '';
    commit = execSync('git rev-parse --short HEAD', { cwd: root('.') }).toString().trim() + dirty;
  } catch {
    if (existsSync(root('.azwall-commit'))) commit = readFileSync(root('.azwall-commit'), 'utf8').trim();
  }
  return `${version}+${commit}`;
}

export default defineConfig({
  base: './',
  define: { __AZWALL_BUILD__: JSON.stringify(buildId()) },
  plugins: [...polyfills(), sqliteWasm()],
  build: {
    outDir: fileURLToPath(new URL('../../assets/pxe/v5', import.meta.url)),
    emptyOutDir: true,
    target: 'esnext',
    chunkSizeWarningLimit: 20_000,
    rolldownOptions: { transform: { inject } },
  },
  worker: { format: 'es', plugins: polyfills, rolldownOptions: { transform: { inject } } },
  optimizeDeps: {
    include: ['pino', 'pino/browser'],
    exclude: [
      '@aztec/noir-noirc_abi',
      '@aztec/noir-acvm_js',
      '@aztec/bb.js',
      '@aztec/noir-noir_js',
      '@aztec/kv-store',
    ],
  },
});
