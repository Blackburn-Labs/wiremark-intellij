// DOM-less tests for the split-editor preview entry (src/main/resources/web/wiremark-preview.js).
//
// We run the preview script inside a Node `vm` context with a hand-rolled
// minimal DOM (only what the script touches: getElementById, classList,
// textContent/innerHTML, style) plus a stub `window.wiremark`. This lets us
// assert window.renderWiremark behavior without a browser:
//   - a successful render swaps in the SVG and renders soft diagnostics
//   - a thrown WiremarkError (NOT a standard Error: e instanceof Error === false)
//     surfaces e.message and KEEPS the last good SVG (panel never blanks)
//   - the error banner clears on the next successful render
//
// Run: node --test src/test/js/wiremark-preview.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const PREVIEW_PATH = join(here, "../../main/resources/web/wiremark-preview.js");
const previewSource = readFileSync(PREVIEW_PATH, "utf8");

// --- Minimal DOM ----------------------------------------------------------

class ClassList {
  constructor() {
    this.set = new Set();
  }
  add(c) {
    this.set.add(c);
  }
  remove(c) {
    this.set.delete(c);
  }
  contains(c) {
    return this.set.has(c);
  }
}

class Element {
  constructor(id) {
    this.id = id;
    this.classList = new ClassList();
    this.style = {};
    this._textContent = "";
    this.innerHTML = "";
    this.children = [];
  }
  set textContent(v) {
    this._textContent = v;
    this.innerHTML = "";
    this.children = [];
  }
  get textContent() {
    return this._textContent;
  }
  appendChild(child) {
    this.children.push(child);
    return child;
  }
}

function makeDocument() {
  const ids = [
    "wiremark-root",
    "wiremark-error",
    "wiremark-host",
    "wiremark-diagnostics",
    "wiremark-placeholder",
  ];
  const els = new Map(ids.map((id) => [id, new Element(id)]));
  return {
    _els: els,
    getElementById(id) {
      return els.get(id) || null;
    },
    createElement(tag) {
      return new Element("<" + tag + ">");
    },
  };
}

// The shared helper module (wiremark-ui.js) defines window.WiremarkUI, which the
// preview script now uses for escapeHtml / diagnosticsHtml / messageOf. We load
// it into the same context first so the preview exercises the real shared code
// (its formatting is what both surfaces share).
const UI_PATH = join(here, "../../main/resources/web/wiremark-ui.js");
const uiSource = readFileSync(UI_PATH, "utf8");

// Run the shared UI helper + the preview IIFE in a fresh context, returning the
// window it populated.
function loadPreview(wiremarkStub) {
  const document = makeDocument();
  const window = { wiremark: wiremarkStub };
  const sandbox = { window, document };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(uiSource, sandbox); // defines window.WiremarkUI
  vm.runInContext(previewSource, sandbox);
  return { window, document };
}

// A WiremarkError as the bundle actually throws it: NOT a subclass of Error.
function makeWiremarkError(message) {
  const e = Object.create(null);
  e.name = "WiremarkError";
  e.message = message;
  return e;
}

// --- Tests ----------------------------------------------------------------

test("renderWiremark swaps in the SVG and clears any error", () => {
  const { window, document } = loadPreview({
    render: () => ({ svg: "<svg id='ok'></svg>", diagnostics: [] }),
  });
  window.renderWiremark("Frame");
  assert.equal(document.getElementById("wiremark-host").innerHTML, "<svg id='ok'></svg>");
  assert.equal(document.getElementById("wiremark-error").classList.contains("visible"), false);
  assert.equal(document.getElementById("wiremark-placeholder").style.display, "none");
});

test("soft diagnostics render into the diagnostics container (shared builder markup)", () => {
  const { window, document } = loadPreview({
    render: () => ({
      svg: "<svg></svg>",
      diagnostics: [{ severity: "warning", message: "missing target", loc: { line: 3 } }],
    }),
  });
  window.renderWiremark("Frame");
  // renderDiagnostics now delegates to window.WiremarkUI.diagnosticsHtml, which
  // emits the same `<ul class="wiremark-diagnostics">` markup as the markdown
  // surface (so both look identical). The container holds that markup as HTML.
  const diags = document.getElementById("wiremark-diagnostics");
  assert.match(diags.innerHTML, /<ul class="wiremark-diagnostics">/);
  assert.match(diags.innerHTML, /<li class="wiremark-diagnostic-warning">missing target \(line 3\)<\/li>/);
});

test("no diagnostics clears the container to empty", () => {
  const { window, document } = loadPreview({
    render: () => ({ svg: "<svg></svg>", diagnostics: [] }),
  });
  window.renderWiremark("Frame");
  assert.equal(document.getElementById("wiremark-diagnostics").innerHTML, "");
});

test("a thrown WiremarkError surfaces its message (not instanceof Error)", () => {
  const err = makeWiremarkError("tabs are not allowed in indentation (line 2)");
  // sanity: this is exactly dev1's gotcha -- it is NOT a standard Error
  assert.equal(err instanceof Error, false);
  const { window, document } = loadPreview({
    render: () => {
      throw err;
    },
  });
  window.renderWiremark("bad source");
  const box = document.getElementById("wiremark-error");
  assert.equal(box.classList.contains("visible"), true);
  // The banner wraps the (escaped) message in .wiremark-error-message, matching
  // the markdown surface; the shared CSS adds the "Error:" lead-in via ::before.
  assert.match(
    box.innerHTML,
    /<div class="wiremark-error-message">tabs are not allowed in indentation \(line 2\)<\/div>/,
  );
});

test("the error banner escapes HTML in the message (XSS-safe)", () => {
  const { window, document } = loadPreview({
    render: () => {
      throw makeWiremarkError("<img src=x onerror=alert(1)>");
    },
  });
  window.renderWiremark("bad source");
  const box = document.getElementById("wiremark-error");
  assert.ok(!/<img/.test(box.innerHTML), "raw <img> must not appear in the banner");
  assert.match(box.innerHTML, /&lt;img/);
});

test("error keeps the last good SVG; next success clears the banner", () => {
  let mode = "ok";
  const { window, document } = loadPreview({
    render: () => {
      if (mode === "boom") throw makeWiremarkError("boom");
      return { svg: "<svg id='good'></svg>", diagnostics: [] };
    },
  });
  // first: good render
  window.renderWiremark("good");
  assert.equal(document.getElementById("wiremark-host").innerHTML, "<svg id='good'></svg>");
  // then: a hard error -- host SVG must be UNCHANGED (no blank panel)
  mode = "boom";
  window.renderWiremark("bad");
  assert.equal(document.getElementById("wiremark-host").innerHTML, "<svg id='good'></svg>");
  assert.equal(document.getElementById("wiremark-error").classList.contains("visible"), true);
  // recover: a good render clears the banner
  mode = "ok";
  window.renderWiremark("good again");
  assert.equal(document.getElementById("wiremark-error").classList.contains("visible"), false);
});

test("renderWiremark guards a missing/broken bundle without throwing", () => {
  const { window, document } = loadPreview(undefined); // no window.wiremark
  window.renderWiremark("anything");
  assert.equal(document.getElementById("wiremark-error").classList.contains("visible"), true);
});
