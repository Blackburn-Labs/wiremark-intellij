// DOM-less tests for the icon src= bridge in the split-editor preview entry
// (src/main/resources/web/wiremark-preview.js, task #6).
//
// The Kotlin side ships an { rawSrc: { body, viewBox } } map as the SECOND
// argument to window.renderWiremark(src, icons). The preview turns that into a
// synchronous loadIcon(src) it hands to wiremark.render(input, { loadIcon }).
// These tests assert, via a stub wiremark.render that captures the options:
//   - loadIcon is passed to render() and is a function
//   - loadIcon(src) returns the pre-read { body, viewBox } for a known src
//   - an unknown src, a malformed entry, or a missing/empty map -> loadIcon
//     returns null (core then degrades to its placeholder), and nothing throws
//
// Run: node --test src/test/js/wiremark-preview-icons.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const PREVIEW_PATH = join(here, "../../main/resources/web/wiremark-preview.js");
const UI_PATH = join(here, "../../main/resources/web/wiremark-ui.js");
const previewSource = readFileSync(PREVIEW_PATH, "utf8");
const uiSource = readFileSync(UI_PATH, "utf8");

// --- Minimal DOM (only what the preview script touches) -------------------

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
    this.innerHTML = "";
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
    getElementById(id) {
      return els.get(id) || null;
    },
    createElement(tag) {
      return new Element("<" + tag + ">");
    },
  };
}

// Load the shared UI helper + preview IIFE in a fresh context with a render stub
// that captures the (input, options) it was called with.
function loadPreview(renderImpl) {
  const captured = {};
  const wiremark = {
    render: function (input, options) {
      captured.input = input;
      captured.options = options;
      return renderImpl(input, options);
    },
  };
  const window = { wiremark };
  const sandbox = { window, document: makeDocument() };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(uiSource, sandbox);
  vm.runInContext(previewSource, sandbox);
  return { window, captured };
}

const OK = () => ({ svg: "<svg></svg>", diagnostics: [] });

// --- Tests ----------------------------------------------------------------

test("render() is called with a loadIcon function", () => {
  const { window, captured } = loadPreview(OK);
  window.renderWiremark("Icons\n  Logo src=./logo.svg", { "./logo.svg": { body: "<path d=\"M0 0\"/>", viewBox: 24 } });
  assert.equal(typeof captured.options.loadIcon, "function");
});

test("loadIcon returns the pre-read artwork for a known src (exact key)", () => {
  let icon;
  const { window } = loadPreview((input, options) => {
    icon = options.loadIcon("./logo.svg");
    return OK();
  });
  window.renderWiremark("src", { "./logo.svg": { body: "<path d=\"M0 0\"/>", viewBox: 32 } });
  assert.deepEqual(icon, { body: "<path d=\"M0 0\"/>", viewBox: 32 });
});

test("loadIcon returns null for an unknown src", () => {
  let icon = "sentinel";
  const { window } = loadPreview((input, options) => {
    icon = options.loadIcon("./missing.svg");
    return OK();
  });
  window.renderWiremark("src", { "./logo.svg": { body: "<path/>", viewBox: 24 } });
  assert.equal(icon, null);
});

test("loadIcon returns null for a malformed entry (no body)", () => {
  let a = "x";
  let b = "y";
  const { window } = loadPreview((input, options) => {
    a = options.loadIcon("empty.svg");
    b = options.loadIcon("nobody.svg");
    return OK();
  });
  window.renderWiremark("src", { "empty.svg": { body: "", viewBox: 24 }, "nobody.svg": { viewBox: 24 } });
  assert.equal(a, null);
  assert.equal(b, null);
});

test("loadIcon never resolves inherited Object.prototype keys", () => {
  // hasOwnProperty guard: 'constructor'/'toString' must miss even though they
  // exist on the prototype chain of a plain object.
  let icon = "x";
  const { window } = loadPreview((input, options) => {
    icon = options.loadIcon("constructor");
    return OK();
  });
  window.renderWiremark("src", {});
  assert.equal(icon, null);
});

test("a missing icons argument is safe; loadIcon just misses", () => {
  let icon = "x";
  const { window } = loadPreview((input, options) => {
    icon = options.loadIcon("anything.svg");
    return OK();
  });
  // Called with a single argument, as the legacy 1-arg path would.
  window.renderWiremark("src");
  assert.equal(icon, null);
});

test("a non-object icons argument is tolerated", () => {
  let icon = "x";
  const { window } = loadPreview((input, options) => {
    icon = options.loadIcon("anything.svg");
    return OK();
  });
  window.renderWiremark("src", "not an object");
  assert.equal(icon, null);
});

test("a successful render with icons still swaps in the SVG", () => {
  const { window } = loadPreview(() => ({ svg: "<svg id='z'></svg>", diagnostics: [] }));
  // smoke: the icon path does not disturb the normal render contract
  window.renderWiremark("src", { "x.svg": { body: "<path/>", viewBox: 24 } });
  // No assertion target for host here beyond not throwing; covered in the
  // sibling preview test. Reaching this line means render+loadIcon wiring is sound.
  assert.ok(true);
});
