// Tests for the shared diagnostics/error formatting helpers
// (src/main/resources/web/wiremark-ui.js), used by BOTH preview surfaces.
//
// The module is an IIFE that assigns window.WiremarkUI. We run it in a Node `vm`
// context with a bare `window` object and assert the helpers directly -- no DOM
// needed, since these are pure string functions.
//
// Run: node --test src/test/js/wiremark-ui.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const UI_PATH = join(here, "../../main/resources/web/wiremark-ui.js");
const uiSource = readFileSync(UI_PATH, "utf8");

function loadUI() {
  const window = {};
  const sandbox = { window };
  vm.createContext(sandbox);
  vm.runInContext(uiSource, sandbox);
  return window.WiremarkUI;
}

// --- escapeHtml -----------------------------------------------------------

test("escapeHtml escapes all five HTML-significant characters", () => {
  const UI = loadUI();
  assert.equal(UI.escapeHtml(`&<>"'`), "&amp;&lt;&gt;&quot;&#39;");
});

test("escapeHtml neutralizes an injection payload", () => {
  const UI = loadUI();
  const out = UI.escapeHtml('<img src=x onerror="alert(1)">');
  assert.ok(!/<img/.test(out), "raw tag must not survive");
  assert.match(out, /&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt;/);
});

test("escapeHtml coerces non-strings without throwing", () => {
  const UI = loadUI();
  assert.equal(UI.escapeHtml(42), "42");
  assert.equal(UI.escapeHtml(null), "null");
  assert.equal(UI.escapeHtml(undefined), "undefined");
});

// --- normalizeDiagnostic --------------------------------------------------

test("normalizeDiagnostic defaults unknown/missing severity to warning", () => {
  const UI = loadUI();
  assert.equal(UI.normalizeDiagnostic({ message: "x" }).severity, "warning");
  assert.equal(UI.normalizeDiagnostic({ severity: "bogus", message: "x" }).severity, "warning");
  assert.equal(UI.normalizeDiagnostic({ severity: "error", message: "x" }).severity, "error");
});

test("normalizeDiagnostic appends a (line N) suffix only when a line is present", () => {
  const UI = loadUI();
  assert.equal(UI.normalizeDiagnostic({ message: "no loc" }).text, "no loc");
  assert.equal(UI.normalizeDiagnostic({ message: "x", loc: {} }).text, "x");
  assert.equal(UI.normalizeDiagnostic({ message: "x", loc: { line: 7 } }).text, "x (line 7)");
});

test("normalizeDiagnostic escapes the message text", () => {
  const UI = loadUI();
  const out = UI.normalizeDiagnostic({ message: "<b>bad</b>", loc: { line: 2 } });
  assert.equal(out.text, "&lt;b&gt;bad&lt;/b&gt; (line 2)");
});

test("normalizeDiagnostic drops a non-numeric loc.line (XSS via the line suffix)", () => {
  // Regression (skeptic): loc.line is interpolated into the "(line N)" suffix,
  // which lands in innerHTML. message is escaped but the suffix is not, so a
  // hostile non-numeric loc.line must be dropped (coerced away), not rendered.
  const UI = loadUI();
  const out = UI.normalizeDiagnostic({
    message: "ok",
    loc: { line: '<img src=x onerror=alert(1)>' },
  });
  assert.ok(!/<img/.test(out.text), "raw <img> from loc.line must not survive");
  assert.equal(out.text, "ok", "non-numeric line is dropped, leaving just the message");
});

test("normalizeDiagnostic keeps a numeric loc.line of 0 distinct from missing", () => {
  // typeof check (not truthiness) so a legitimate line 0 isn't dropped as falsy.
  const UI = loadUI();
  assert.equal(UI.normalizeDiagnostic({ message: "x", loc: { line: 0 } }).text, "x (line 0)");
});

// --- diagnosticsHtml ------------------------------------------------------

test("diagnosticsHtml returns empty string for no diagnostics", () => {
  const UI = loadUI();
  assert.equal(UI.diagnosticsHtml(undefined), "");
  assert.equal(UI.diagnosticsHtml(null), "");
  assert.equal(UI.diagnosticsHtml([]), "");
});

test("diagnosticsHtml builds the shared <ul class=wiremark-diagnostics> markup", () => {
  const UI = loadUI();
  const html = UI.diagnosticsHtml([
    { severity: "warning", message: "unknown background", loc: { line: 3 } },
    { severity: "error", message: "duplicate id", loc: { line: 9 } },
  ]);
  assert.match(html, /^<ul class="wiremark-diagnostics">/);
  assert.match(html, /<li class="wiremark-diagnostic-warning">unknown background \(line 3\)<\/li>/);
  assert.match(html, /<li class="wiremark-diagnostic-error">duplicate id \(line 9\)<\/li>/);
  assert.match(html, /<\/ul>$/);
});

test("diagnosticsHtml escapes message text (XSS-safe footer)", () => {
  const UI = loadUI();
  const html = UI.diagnosticsHtml([{ severity: "warning", message: "<script>x</script>" }]);
  assert.ok(!/<script>/.test(html), "raw <script> must not appear");
  assert.match(html, /&lt;script&gt;x&lt;\/script&gt;/);
});

// --- errorHtml ------------------------------------------------------------

test("errorHtml wraps the message in .wiremark-error-message", () => {
  const UI = loadUI();
  assert.equal(
    UI.errorHtml("tabs not allowed (line 2)"),
    '<div class="wiremark-error-message">tabs not allowed (line 2)</div>',
  );
});

test("errorHtml escapes the message (XSS-safe banner, both surfaces)", () => {
  const UI = loadUI();
  const html = UI.errorHtml('<img src=x onerror=alert(1)>');
  assert.ok(!/<img/.test(html), "raw <img> must not appear in the banner");
  assert.match(html, /<div class="wiremark-error-message">&lt;img src=x onerror=alert\(1\)&gt;<\/div>/);
});

// --- messageOf ------------------------------------------------------------

test("messageOf reads .message from a WiremarkError-shaped throwable", () => {
  const UI = loadUI();
  // A WiremarkError is NOT instanceof Error; messageOf must not rely on that.
  const err = Object.create(null);
  err.name = "WiremarkError";
  err.message = "tabs not allowed (line 2)";
  assert.equal(UI.messageOf(err, "fallback"), "tabs not allowed (line 2)");
});

test("messageOf accepts a bare string throwable", () => {
  const UI = loadUI();
  assert.equal(UI.messageOf("boom", "fallback"), "boom");
});

test("messageOf falls back for an unexpected throwable (never [object Object])", () => {
  const UI = loadUI();
  assert.equal(UI.messageOf({}, "fallback msg"), "fallback msg");
  assert.equal(UI.messageOf(null, "fallback msg"), "fallback msg");
  // Default fallback when none supplied.
  assert.match(UI.messageOf({}), /failed to render/i);
});
