// DOM-less tests for the markdown-preview glue (src/main/resources/web/wiremark-glue.js).
//
// We run the glue inside a Node `vm` context with a hand-rolled minimal DOM that
// implements only what the glue touches: querySelectorAll, closest, classList,
// attributes, createElement/insertBefore, and a no-op MutationObserver. This lets
// us assert the glue's load-time render pass behavior without a browser:
//   - a wireframe fence renders its SVG into a sibling host and hides the source
//   - re-running is idempotent (no duplicate hosts; no re-render when unchanged)
//   - changing the fence text re-renders
//   - a WiremarkError keeps the source visible and shows the message
//   - data-line attributes are carried to the host for scroll sync
//
// Run: node --test src/test/js/wiremark-glue.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const GLUE_PATH = join(here, "../../main/resources/web/wiremark-glue.js");
const glueSource = readFileSync(GLUE_PATH, "utf8");
// The glue now formats diagnostics/escapes text via the shared helper module
// (window.WiremarkUI). Load it into the same sandbox so the glue exercises the
// real shared code, exactly as it does in the browser.
const UI_PATH = join(here, "../../main/resources/web/wiremark-ui.js");
const uiSource = readFileSync(UI_PATH, "utf8");

// --- Minimal DOM ----------------------------------------------------------

class ClassList {
  constructor(el) {
    this.el = el;
    this.set = new Set();
  }
  add(c) {
    this.set.add(c);
    this.el._syncClassAttr();
  }
  remove(c) {
    this.set.delete(c);
    this.el._syncClassAttr();
  }
  contains(c) {
    return this.set.has(c);
  }
}

class Element {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.parentNode = null;
    this._attrs = new Map();
    this.classList = new ClassList(this);
    this._text = "";
    this.innerHTML = "";
  }

  // attributes mirror the live attribute list the glue iterates over.
  get attributes() {
    return [...this._attrs.entries()].map(([name, value]) => ({ name, value }));
  }
  setAttribute(name, value) {
    this._attrs.set(name, String(value));
    if (name === "class") this._reloadClassList(String(value));
  }
  getAttribute(name) {
    return this._attrs.has(name) ? this._attrs.get(name) : null;
  }
  _reloadClassList(value) {
    this.classList.set = new Set(value.split(/\s+/).filter(Boolean));
  }
  _syncClassAttr() {
    this._attrs.set("class", [...this.classList.set].join(" "));
  }

  set className(value) {
    this.setAttribute("class", value);
  }
  get className() {
    return this.getAttribute("class") || "";
  }

  set id(value) {
    this.setAttribute("id", value);
  }
  get id() {
    return this.getAttribute("id") || "";
  }

  get textContent() {
    if (this.children.length === 0) return this._text;
    return this.children.map((c) => c.textContent).join("");
  }
  set textContent(value) {
    this._text = value;
    this.children = [];
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  insertBefore(node, ref) {
    // Real DOM semantics: if `node` is already in the tree, it is MOVED, not
    // duplicated. Detach from its current parent first.
    if (node.parentNode) node.parentNode.removeChild(node);
    node.parentNode = this;
    if (ref == null) {
      this.children.push(node);
      return node;
    }
    const i = this.children.indexOf(ref);
    if (i === -1) this.children.push(node);
    else this.children.splice(i, 0, node);
    return node;
  }

  get nextSibling() {
    if (!this.parentNode) return null;
    const i = this.parentNode.children.indexOf(this);
    return this.parentNode.children[i + 1] || null;
  }
  get nextElementSibling() {
    return this.nextSibling; // every node in this shim is an element
  }
  get previousSibling() {
    if (!this.parentNode) return null;
    const i = this.parentNode.children.indexOf(this);
    return i > 0 ? this.parentNode.children[i - 1] : null;
  }
  get previousElementSibling() {
    return this.previousSibling; // every node in this shim is an element
  }

  removeChild(child) {
    const i = this.children.indexOf(child);
    if (i !== -1) this.children.splice(i, 1);
    child.parentNode = null;
    return child;
  }

  closest(selector) {
    let node = this;
    const tag = selector.toUpperCase();
    while (node) {
      if (node.tagName === tag) return node;
      node = node.parentNode;
    }
    return null;
  }

  // Mirrors the glue's selector `code[class*="language-"]` (the broad candidate
  // set). The glue's own isWireframeFence() does the precise filtering, so this
  // returns every language-tagged <code> to exercise that filter.
  _matchesFence() {
    if (this.tagName !== "CODE") return false;
    for (const cls of this.classList.set) {
      if (cls.indexOf("language-") === 0) return true;
    }
    return false;
  }

  _collect(out) {
    if (this._matchesFence()) out.push(this);
    for (const c of this.children) c._collect(out);
  }

  // Depth-first walk applying an arbitrary predicate; used by querySelectorAll.
  _walk(pred, out) {
    if (pred(this)) out.push(this);
    for (const c of this.children) c._walk(pred, out);
  }
}

function makeDocument() {
  const body = new Element("body");
  const head = new Element("head");
  return {
    body,
    head,
    readyState: "complete",
    createElement: (tag) => new Element(tag),
    getElementById: (id) => {
      // Only the base-style <style> is looked up by id; search head children.
      return head.children.find((c) => c.getAttribute("id") === id) || null;
    },
    querySelectorAll: (selector) => {
      const out = [];
      // The glue uses exactly two selector shapes:
      //   code[class*="language-"]                    -> fence candidates
      //   .wiremark-host[data-wiremark-id="<id>"]      -> host lookup by pair id
      const hostMatch = selector.match(
        /^\.wiremark-host\[data-wiremark-id="([^"]*)"\]$/
      );
      if (hostMatch) {
        const id = hostMatch[1];
        body._walk(
          (el) =>
            el.classList.contains("wiremark-host") &&
            el.getAttribute("data-wiremark-id") === id,
          out
        );
        return out;
      }
      // Default: the fence-candidate selector.
      body._collect(out);
      return out;
    },
    addEventListener: () => {},
  };
}

// Build: <pre data-line=N><code class="language-X">SOURCE</code></pre>, attached to body.
function addFence(doc, lang, source, dataLine) {
  const pre = new Element("pre");
  if (dataLine != null) pre.setAttribute("data-line", String(dataLine));
  const code = new Element("code");
  code.setAttribute("class", "language-" + lang);
  code.textContent = source;
  pre.appendChild(code);
  doc.body.appendChild(pre);
  return { pre, code };
}

function runGlue(doc, wiremark) {
  const sandbox = {
    document: doc,
    window: {
      wiremark,
      requestAnimationFrame: (fn) => fn(), // run scheduled work synchronously
    },
    MutationObserver: class {
      observe() {}
      disconnect() {}
    },
    setTimeout: (fn) => fn(),
  };
  vm.createContext(sandbox);
  // Shared helper first (defines window.WiremarkUI), then the glue -- matching
  // the browser load order (wiremark-ui.js is listed before wiremark-glue.js).
  vm.runInContext(uiSource, sandbox, { filename: "wiremark-ui.js" });
  vm.runInContext(glueSource, sandbox, { filename: "wiremark-glue.js" });
  return sandbox;
}

function hostAfter(pre) {
  const next = pre.nextElementSibling;
  return next && next.classList.contains("wiremark-host") ? next : null;
}

// --- Tests ----------------------------------------------------------------

test("renders a wireframe fence into a sibling host and hides the source", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframe", "Frame\n  Button 'OK'");
  runGlue(doc, { render: () => ({ svg: "<svg id='ok'></svg>", diagnostics: [] }) });

  const host = hostAfter(pre);
  assert.ok(host, "a wiremark-host sibling should be created");
  assert.match(host.innerHTML, /<svg id='ok'><\/svg>/);
  assert.ok(pre.classList.contains("wiremark-source-hidden"), "source <pre> hidden");
  assert.ok(!host.classList.contains("wiremark-error"));
});

test("accepts the `wiremark` fence info string as an alias", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "wiremark", "Frame");
  runGlue(doc, { render: () => ({ svg: "<svg/>", diagnostics: [] }) });
  assert.ok(hostAfter(pre), "language-wiremark should also render");
});

test("matches a hyphen-joined multi-word fence info (language-wireframe-foo)", () => {
  // ```wireframe foo  =>  class="language-wireframe-foo" (info is hyphen-joined).
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframe-foo", "Frame");
  runGlue(doc, { render: () => ({ svg: "<svg/>", diagnostics: [] }) });
  assert.ok(hostAfter(pre), "first hyphen-segment 'wireframe' should match");
});

test("matches case-insensitively (language-Wireframe)", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "Wireframe", "Frame");
  runGlue(doc, { render: () => ({ svg: "<svg/>", diagnostics: [] }) });
  assert.ok(hostAfter(pre), "capitalized fence info should still match");
});

test("does not match Object.prototype keys (language-constructor, language-toString)", () => {
  // Regression: the accept test must not be an object-map lookup, or inherited
  // prototype keys would falsely match and shove a normal code block into render().
  for (const lang of ["constructor", "toString", "hasOwnProperty", "valueOf"]) {
    const doc = makeDocument();
    const { pre } = addFence(doc, lang, "whatever");
    let called = 0;
    runGlue(doc, {
      render: () => {
        called++;
        return { svg: "<svg/>", diagnostics: [] };
      },
    });
    assert.equal(called, 0, `language-${lang} must not be treated as a wireframe`);
    assert.equal(hostAfter(pre), null, `language-${lang} must not get a host`);
  }
});

test("does not match a language that merely starts with wireframe-ish text", () => {
  // "wireframely" must NOT match: head segment is "wireframely", not "wireframe".
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframely", "Frame");
  let called = 0;
  runGlue(doc, {
    render: () => {
      called++;
      return { svg: "<svg/>", diagnostics: [] };
    },
  });
  assert.equal(called, 0);
  assert.equal(hostAfter(pre), null);
});

test("ignores unrelated code fences", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "javascript", "const x = 1;");
  let called = 0;
  runGlue(doc, {
    render: () => {
      called++;
      return { svg: "<svg/>", diagnostics: [] };
    },
  });
  assert.equal(called, 0, "render must not run for non-wireframe fences");
  assert.equal(hostAfter(pre), null);
});

test("is idempotent: a second render pass adds no second host and does not re-render", () => {
  const doc = makeDocument();
  const { pre, code } = addFence(doc, "wireframe", "Frame");
  let calls = 0;
  const sandbox = runGlue(doc, {
    render: () => {
      calls++;
      return { svg: "<svg/>", diagnostics: [] };
    },
  });
  assert.equal(calls, 1, "first pass renders once");

  // Re-run the glue against the same DOM (simulating another observer tick). The
  // hash marker on <code> matches, the host already exists, so renderFence must
  // early-return: no new render call, no duplicate host.
  vm.runInContext(glueSource, sandbox, { filename: "wiremark-glue.js" });

  const next = pre.nextElementSibling;
  assert.ok(next && next.classList.contains("wiremark-host"));
  assert.equal(next.nextElementSibling, null, "no duplicate host inserted");
  assert.equal(calls, 1, "unchanged fence is not re-rendered on the second pass");
  assert.ok(code.getAttribute("data-wiremark-hash"));
});

test("re-renders when the fence content changes", () => {
  const doc = makeDocument();
  const { pre, code } = addFence(doc, "wireframe", "Frame A");
  const rendered = [];
  const sandbox = runGlue(doc, {
    render: (src) => {
      rendered.push(src);
      return { svg: "<svg>" + src.length + "</svg>", diagnostics: [] };
    },
  });
  const firstHash = code.getAttribute("data-wiremark-hash");

  // Simulate the incremental updater mutating the fence text in place.
  code.textContent = "Frame B longer";
  vm.runInContext(glueSource, sandbox, { filename: "wiremark-glue.js" });

  const secondHash = code.getAttribute("data-wiremark-hash");
  assert.notEqual(firstHash, secondHash, "hash changes when content changes");
  assert.deepEqual(rendered, ["Frame A", "Frame B longer"]);
  assert.match(hostAfter(pre).innerHTML, /<svg>14<\/svg>/);
});

test("does not create a duplicate host when a foreign node lands between <pre> and host", () => {
  // Skeptic's scenario: after the fence renders, the incremental updater inserts
  // a foreign block (e.g. a paragraph the user typed below the fence) BETWEEN the
  // <pre> and our host -> order becomes pre, foreign, host. An adjacency-only
  // lookup would miss the host and insert a second one that never updates. The
  // id-paired lookup must find the existing host wherever it sits, reuse it, and
  // leave exactly one host.
  const doc = makeDocument();
  const { pre, code } = addFence(doc, "wireframe", "Frame A");
  const rendered = [];
  const sandbox = runGlue(doc, {
    render: (src) => {
      rendered.push(src);
      return { svg: "<svg>" + src + "</svg>", diagnostics: [] };
    },
  });

  // Inject a foreign <p> between the <pre> and the host.
  const host = pre.nextElementSibling;
  assert.ok(host.classList.contains("wiremark-host"), "host starts adjacent to <pre>");
  const foreign = new Element("p");
  pre.parentNode.insertBefore(foreign, host); // order: pre, foreign, host

  // Now change the fence content and tick again.
  code.textContent = "Frame B";
  vm.runInContext(glueSource, sandbox, { filename: "wiremark-glue.js" });

  const hosts = doc.body.children.filter(
    (c) => c.classList && c.classList.contains("wiremark-host")
  );
  // Also count any nested ones, to be safe.
  const allHosts = [];
  doc.body._walk((el) => el.classList && el.classList.contains("wiremark-host"), allHosts);
  assert.equal(allHosts.length, 1, "exactly one host after the tick (no duplicate)");
  assert.match(allHosts[0].innerHTML, /<svg>Frame B<\/svg>/, "the single host shows updated content");
  assert.deepEqual(rendered, ["Frame A", "Frame B"]);
  // And the host was repositioned back to immediately after the <pre>.
  assert.equal(pre.nextElementSibling, allHosts[0], "host moved back adjacent to <pre>");
});

test("removes an accidental duplicate host, keeping a single live one", () => {
  // Defensive: if a second host with the same pair id somehow exists, findHost
  // must collapse to one rather than leave a frozen stale copy.
  const doc = makeDocument();
  const { pre, code } = addFence(doc, "wireframe", "Frame A");
  const sandbox = runGlue(doc, {
    render: (src) => ({ svg: "<svg>" + src + "</svg>", diagnostics: [] }),
  });
  const host = pre.nextElementSibling;
  const pairId = code.getAttribute("data-wiremark-id");

  // Forge a duplicate host carrying the same pair id, placed elsewhere.
  const dup = new Element("div");
  dup.setAttribute("class", "wiremark-host");
  dup.setAttribute("data-wiremark-id", pairId);
  dup.innerHTML = "<svg>STALE</svg>";
  doc.body.appendChild(dup);

  code.textContent = "Frame B";
  vm.runInContext(glueSource, sandbox, { filename: "wiremark-glue.js" });

  const allHosts = [];
  doc.body._walk((el) => el.classList && el.classList.contains("wiremark-host"), allHosts);
  assert.equal(allHosts.length, 1, "duplicate collapsed to a single host");
  assert.match(allHosts[0].innerHTML, /<svg>Frame B<\/svg>/);
  assert.equal(allHosts[0], host, "the original host is the survivor");
});

test("hard WiremarkError keeps source visible and shows the message", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframe", "\tbad indentation");
  runGlue(doc, {
    render: () => {
      throw new Error("Tabs are not allowed in indentation (line 1)");
    },
  });
  const host = hostAfter(pre);
  assert.ok(host, "host created for the error banner");
  assert.ok(host.classList.contains("wiremark-error"));
  assert.match(host.innerHTML, /Tabs are not allowed in indentation \(line 1\)/);
  assert.ok(!pre.classList.contains("wiremark-source-hidden"), "source stays visible on error");
});

test("escapes HTML in error messages and diagnostics", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframe", "x");
  runGlue(doc, {
    render: () => {
      throw new Error("<img src=x onerror=alert(1)>");
    },
  });
  const host = hostAfter(pre);
  assert.ok(!/<img/.test(host.innerHTML), "raw <img> must not appear");
  assert.match(host.innerHTML, /&lt;img/);
});

test("renders a soft-diagnostics footer when diagnostics are present", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframe", "Frame");
  runGlue(doc, {
    render: () => ({
      svg: "<svg/>",
      diagnostics: [{ severity: "warning", message: "unknown background", loc: { line: 3 } }],
    }),
  });
  const host = hostAfter(pre);
  assert.match(host.innerHTML, /wiremark-diagnostics/);
  assert.match(host.innerHTML, /unknown background \(line 3\)/);
  assert.match(host.innerHTML, /wiremark-diagnostic-warning/);
});

test("carries data-line from the source <pre> to the host for scroll sync", () => {
  const doc = makeDocument();
  const { pre } = addFence(doc, "wireframe", "Frame", 42);
  runGlue(doc, { render: () => ({ svg: "<svg/>", diagnostics: [] }) });
  assert.equal(hostAfter(pre).getAttribute("data-line"), "42");
});

test("does not inject any <style> (styling moved to the shared wiremark-ui.css)", () => {
  // The load-bearing base rules and the richer diagnostics/error styling now
  // live in wiremark-ui.css (injected as a <link> by the platform via
  // WiremarkPreviewExtension.styles). The glue must not inject a <style> of its
  // own anymore -- doing so would re-create the duplicate dev3's contract warned
  // against.
  const doc = makeDocument();
  addFence(doc, "wireframe", "Frame");
  runGlue(doc, { render: () => ({ svg: "<svg/>", diagnostics: [] }) });
  assert.equal(
    doc.head.children.filter((c) => c.tagName === "STYLE").length,
    0,
    "glue injects no <style>",
  );
});
