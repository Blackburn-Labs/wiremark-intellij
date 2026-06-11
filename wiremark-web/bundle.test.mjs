// Smoke test for the wiremark browser bundle. Fails the build if the bundle is
// broken: it builds the IIFE, evaluates it in a bare `vm` context (no DOM, the
// way JCEF would only have what the bundle itself provides), and asserts the
// `wiremark.render` contract the plugin depends on.
//
// Run via `npm test` (node --test) in wiremark-web/; Gradle wires this into
// `check`. We build into the OS temp dir so the test is self-contained and does
// not depend on the Gradle-driven build having run first.

import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import vm from 'node:vm';

// A minimal valid wiremark source (a single frame with one component); see the
// @wiremark/core README. render() must return SVG with no diagnostics for it.
const MINIMAL_SOURCE = 'Wireframe mobile\n  Button "OK"';

// A source with a SOFT problem (unknown icon): core degrades gracefully --
// renders a placeholder and reports it in `diagnostics` rather than throwing.
// Used to lock in the second half of the {svg, diagnostics} contract that the
// plugin's error/diagnostics UI depends on.
const SOFT_WARNING_SOURCE = 'Wireframe mobile\n  Icon name=__no_such_icon__';

let wiremark;

before(() => {
  const out = join(mkdtempSync(join(tmpdir(), 'wiremark-web-')), 'wiremark.browser.js');
  execFileSync(process.execPath, ['build.mjs', out], { stdio: 'pipe' });

  const code = readFileSync(out, 'utf8');
  // Bare sandbox on purpose: if the bundle reached for a browser/Node global at
  // load time, this would throw and fail the test.
  const sandbox = {};
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  wiremark = sandbox.wiremark;
});

test('bundle exposes the wiremark global with render()', () => {
  assert.equal(typeof wiremark, 'object');
  assert.equal(typeof wiremark.render, 'function');
});

test('render(minimal source) returns non-empty svg and a diagnostics array', () => {
  const result = wiremark.render(MINIMAL_SOURCE);
  assert.equal(typeof result, 'object');
  assert.equal(typeof result.svg, 'string');
  assert.ok(result.svg.length > 0, 'svg should be non-empty');
  assert.ok(result.svg.includes('<svg'), 'svg should contain an <svg> element');
  assert.ok(Array.isArray(result.diagnostics), 'diagnostics should be an array');
});

test('a soft problem still renders svg but reports a diagnostic', () => {
  const result = wiremark.render(SOFT_WARNING_SOURCE);
  // Soft warnings must NOT throw and must still produce SVG.
  assert.ok(result.svg.includes('<svg'), 'svg should still render despite the warning');
  // ...and the warning must surface in diagnostics, each carrying a message.
  assert.ok(result.diagnostics.length > 0, 'a soft problem should yield >= 1 diagnostic');
  assert.equal(typeof result.diagnostics[0].message, 'string', 'diagnostic should have a message string');
  assert.ok(result.diagnostics[0].message.length > 0, 'diagnostic message should be non-empty');
});
