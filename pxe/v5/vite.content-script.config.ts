// Builds the dApp content script (src/dapp/content_script.ts) as one self-contained IIFE next to the PXE host:
// assets/pxe/v5/dapp-content-script.js. The app injects it into every page of its browser at document start.
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'vite';

export default defineConfig({
  publicDir: false,
  build: {
    target: 'es2020',
    outDir: fileURLToPath(new URL('../../assets/pxe/v5', import.meta.url)),
    emptyOutDir: false,
    minify: true,
    lib: {
      entry: fileURLToPath(new URL('./src/dapp/content_script.ts', import.meta.url)),
      formats: ['iife'],
      name: 'AzwallContentScript',
      fileName: () => 'dapp-content-script.js',
    },
  },
});
