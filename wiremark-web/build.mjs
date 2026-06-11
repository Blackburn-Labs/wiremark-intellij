// Build the wiremark browser bundle: a self-contained IIFE exposing the
// @wiremark/core public API as the global `wiremark`. This is what the plugin
// loads into JCEF (markdown preview + the *.wiremark split editor); see
// tasks/PLAN.md "Upstream prerequisite".
//
// OPTION-A SWITCHOVER: when @wiremark/core starts shipping a prebuilt
// dist/wiremark.browser.js (the "option A" upstream change, in flight but not
// yet published as of 0.0.4), this whole esbuild step can be replaced by a
// copy of node_modules/@wiremark/core/dist/wiremark.browser.js to the same
// output path. The Gradle wiring, resource root, and smoke test stay as-is;
// only ENTRY/this bundling call change. Until then we bundle it ourselves.
//
// Usage: node build.mjs <outfile>
//   <outfile> defaults to dist/wiremark.browser.js (for local/`npm run build`).
//   Gradle passes build/generated/wiremarkWeb/web/wiremark.browser.js.

import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ENTRY = resolve(here, 'node_modules/@wiremark/core/src/index.js');
const outfile = resolve(here, process.argv[2] ?? 'dist/wiremark.browser.js');

await build({
  entryPoints: [ENTRY],
  bundle: true,
  format: 'iife',
  globalName: 'wiremark',
  // JCEF ships a modern Chromium, so a high target keeps the bundle small.
  target: 'es2020',
  minify: true,
  // No sourcemap: the bundle is generated, never committed, and shipping a map
  // to JCEF would just bloat the plugin jar.
  sourcemap: false,
  legalComments: 'none',
  outfile,
});

console.log(`wiremark browser bundle written: ${outfile}`);
