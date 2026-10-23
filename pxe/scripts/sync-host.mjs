// Copies the shared PXE host (pxe/host/src and pxe/host/test) into the version package it runs from (pxe/v6,
// pxe/v7…), with the import names of that version's Aztec packages. The copies are generated and not committed:
// edit pxe/host, and a version's own differences in its src/version.ts.
//
// Import names: package.json's `azwallHost.imports` maps prefixes of `@aztec/…` (the names pxe/host is written
// with) to this version's, first match wins. Without it the names are kept.
import { mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HEADER = '// Generated from pxe/host by pxe/scripts/sync-host.mjs: edit the source there, not this copy.\n';
const host = join(dirname(fileURLToPath(import.meta.url)), '..', 'host');
const target = process.cwd();
const rules = Object.entries(JSON.parse(readFileSync(join(target, 'package.json'), 'utf8')).azwallHost?.imports ?? {});

/** `from '@aztec/x'`, `import '@aztec/x'` and `import('@aztec/x')`, renamed with the first matching rule. */
export function rename(source, imports = rules) {
  return source.replace(/((?:from|import)\s*\(?\s*)'(@aztec\/[^']+)'/g, (_m, lead, name) => {
    const rule = imports.find(([prefix]) => name.startsWith(prefix));
    return `${lead}'${rule ? rule[1] + name.slice(rule[0].length) : name}'`;
  });
}

function files(dir) {
  return readdirSync(dir).flatMap(f => (statSync(join(dir, f)).isDirectory() ? files(join(dir, f)) : [join(dir, f)]));
}

/** Removes the previous copies only: a file without the header is the version's own. */
function clean(dir) {
  for (const f of files(dir)) if (f.endsWith('.ts') && readFileSync(f, 'utf8').startsWith(HEADER)) rmSync(f);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const part of ['src', 'test']) {
    mkdirSync(join(target, part), { recursive: true });
    clean(join(target, part));
    for (const file of files(join(host, part))) {
      const out = join(target, part, relative(join(host, part), file));
      mkdirSync(dirname(out), { recursive: true });
      writeFileSync(out, HEADER + rename(readFileSync(file, 'utf8')));
    }
  }
}
