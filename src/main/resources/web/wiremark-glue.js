// wiremark markdown-preview glue.
//
// The JetBrains Markdown preview is a JCEF (Chromium) panel whose DOM is patched
// *incrementally* while you type (the platform's IncrementalDOMBuilder reconciles
// the live DOM against the re-parsed document rather than replacing it). Two
// consequences drive the design here:
//
//   1. We must be observer-driven and idempotent: the same fence node survives
//      across edits, its text content mutated in place, so a one-shot "rendered"
//      flag is not enough -- we key on a hash of the fence's text and re-render
//      only when that text changes.
//
//   2. We must not remove the source <pre>. The incremental updater can resurrect
//      a node it still expects to exist, which would fight us (flicker / infinite
//      churn). Instead we keep the <pre> in place, hide it with a CSS class, and
//      render into a sibling host element that we own. If the updater ever drops
//      our host, the next observer tick re-creates it -- self-healing.
//
// The core bundle (loaded just before this script) exposes a synchronous global
// `wiremark.render(src, { theme }) -> { svg, diagnostics }`, throwing WiremarkError
// on a hard parse error. We accept both `wireframe` (canonical) and `wiremark`
// fence infos, and pass the theme detected from the page background (see
// detectTheme) so the SVG palette follows the IDE.

(function () {
  "use strict";

  // Fence <code> nodes the preview emits for ```wireframe / ```wiremark blocks.
  // The platform's code-fence generator emits class="language-<info>", where a
  // multi-word info string is hyphen-joined and the info is NOT lowercased (see
  // DefaultCodeFenceGeneratingProvider). So we cast a wide net with the selector
  // and apply the precise, case-insensitive accept test in isWireframeFence():
  // the fence info's first hyphen-segment must be "wireframe" or "wiremark"
  // (canonical bare ```wireframe / ```wiremark, plus a ```wireframe foo form).
  var FENCE_SELECTOR = 'code[class*="language-"]';

  function isWireframeFence(code) {
    var classes = (code.className || "").split(/\s+/);
    for (var i = 0; i < classes.length; i++) {
      var cls = classes[i];
      if (cls.length > 9 && cls.slice(0, 9).toLowerCase() === "language-") {
        // Direct comparison (not an object-map lookup) so inherited
        // Object.prototype keys like "constructor"/"toString" can't falsely
        // match a ```constructor or ```toString fence.
        var head = cls.slice(9).toLowerCase().split("-")[0];
        if (head === "wireframe" || head === "wiremark") return true;
      }
    }
    return false;
  }

  // Marker attributes / classes (kept stable; dev4's diagnostics UI keys off the
  // host structure below).
  var HASH_ATTR = "data-wiremark-hash"; // last-rendered content hash + theme, on the <code>
  var PAIR_ATTR = "data-wiremark-id"; // stable id linking a <code> to its host
  var HOST_CLASS = "wiremark-host"; // our rendered container, sibling of the <pre>
  var SOURCE_HIDDEN_CLASS = "wiremark-source-hidden"; // hides the original <pre>
  var ERROR_CLASS = "wiremark-error"; // hard-error banner (source stays visible)
  var DARK_CLASS = "wiremark-dark"; // on the host when core rendered the dark palette
  // The soft-diagnostics footer markup (class "wiremark-diagnostics") is built by
  // the shared formatter in wiremark-ui.js; this file no longer names that class.

  // Monotonic id source for pairing a fence's <code> to its rendered host. We
  // pair by id rather than DOM adjacency because the incremental updater can
  // insert a foreign node (e.g. a paragraph the user just typed below the fence)
  // between the <pre> and our host; an adjacency check would then miss the host
  // and create a persistent duplicate. An id lookup finds it wherever it lands.
  var nextPairId = 0;

  // Shared formatting helpers (wiremark-ui.js, loaded before this script):
  // escapeHtml() and the diagnostics-footer builder, so both preview surfaces
  // format identically. A tiny local escapeHtml fallback keeps the glue safe if
  // the shared module somehow failed to load (never emit unescaped text).
  var UI = window.WiremarkUI || {};
  var escapeHtml =
    UI.escapeHtml ||
    function (text) {
      return String(text)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
    };

  // Small, stable, dependency-free string hash (FNV-1a, 32-bit). We hash rather
  // than compare the raw source because the change-detection key has to live in
  // an *attribute* on the <code> (HASH_ATTR) -- an attribute survives the
  // incremental updater's reconcile where a JS property would not -- and storing
  // the full source there would duplicate KBs of wireframe text per fence. The
  // hash compresses that key to a few stable bytes. Only needs to change when the
  // fence text changes; collisions are harmless (a stale host is visually
  // identical content anyway), and FNV-1a makes them rare.
  function hashContent(text) {
    var h = 0x811c9dc5;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
    }
    return h.toString(16);
  }

  // Decide light vs dark from the effective background color behind the fences.
  // The markdown preview styles <body> from the active EDITOR COLOR SCHEME
  // (PreviewLAFThemeStyles), so reading it back matches the page the user
  // actually sees. Deliberately NOT matchMedia('prefers-color-scheme'): inside
  // JCEF that tracks the OS scheme, not the IDE's, and is wrong when they
  // differ. Walk up past transparent backgrounds; nothing opaque (or no
  // getComputedStyle, as in the DOM-less tests) defaults to light, core's own
  // fallback. A LAF switch rebuilds this whole panel (scripts re-run fresh), so
  // detection at render time is always current -- no change listener needed.
  function detectTheme() {
    if (typeof window.getComputedStyle !== "function") return "light";
    var el = document.body;
    while (el) {
      var bg = "";
      try {
        bg = String(window.getComputedStyle(el).backgroundColor || "");
      } catch (e) {
        return "light";
      }
      var m = /^rgba?\(\s*([^)]+)\)/.exec(bg);
      if (m) {
        var parts = m[1].split(",");
        var alpha = parts.length > 3 ? parseFloat(parts[3]) : 1;
        var r = parseFloat(parts[0]);
        var g = parseFloat(parts[1]);
        var b = parseFloat(parts[2]);
        if (alpha > 0 && !isNaN(r) && !isNaN(g) && !isNaN(b)) {
          // Perceived luminance (ITU-R BT.601); below mid-gray reads as dark.
          return 0.299 * r + 0.587 * g + 0.114 * b < 128 ? "dark" : "light";
        }
      }
      el = el.parentElement;
    }
    return "light";
  }

  // The <code>'s text is the wireframe source. Browsers may break it across text
  // nodes; textContent reassembles it. Trailing newline from the fence is benign.
  function sourceOf(code) {
    return code.textContent || "";
  }

  // The <pre> wrapping a fence's <code>; the unit we hide and render beside.
  function preOf(code) {
    return code.closest("pre");
  }

  // Carry data-line (and data-original-... line markers) from the source <pre> to
  // our host so the preview's editor<->preview scroll sync keeps targeting this
  // block. We copy rather than move: the <pre> keeps its own attributes (it stays
  // in the DOM, just hidden) and the incremental updater keeps owning it.
  function copyLineAttributes(fromPre, toHost) {
    if (!fromPre || !fromPre.attributes) return;
    for (var i = 0; i < fromPre.attributes.length; i++) {
      var attr = fromPre.attributes[i];
      if (attr.name === "data-line" || attr.name.indexOf("data-line-") === 0) {
        toHost.setAttribute(attr.name, attr.value);
      }
    }
  }

  // Build the soft-diagnostics footer (warnings/errors collected, not thrown).
  // Delegates to the shared formatter (wiremark-ui.js) so both surfaces produce
  // identical `<ul class="wiremark-diagnostics">...` markup; falls back to an
  // empty footer if the shared module is missing (diagnostics are non-critical).
  function diagnosticsHtml(diagnostics) {
    return UI.diagnosticsHtml ? UI.diagnosticsHtml(diagnostics) : "";
  }

  // Stable pairing id for a fence's <code>, assigned on first sight. Survives on
  // an attribute (durable across the incremental updater) so we always find the
  // same fence's host again.
  function pairIdOf(code) {
    var id = code.getAttribute(PAIR_ATTR);
    if (!id) {
      id = String(nextPairId++);
      code.setAttribute(PAIR_ATTR, id);
    }
    return id;
  }

  // Find this fence's host by its pairing id, anywhere in the document (not just
  // as the <pre>'s immediate sibling). If duplicates somehow exist, keep the
  // first and drop the rest so we never leave a frozen stale copy behind.
  function findHost(pairId) {
    var matches = document.querySelectorAll(
      '.' + HOST_CLASS + '[' + PAIR_ATTR + '="' + pairId + '"]'
    );
    if (matches.length === 0) return null;
    for (var i = 1; i < matches.length; i++) {
      if (matches[i].parentNode) matches[i].parentNode.removeChild(matches[i]);
    }
    return matches[0];
  }

  // Render (or re-render) a single fence under [theme]. Idempotent: safe to
  // call every tick.
  function renderFence(code, theme) {
    var pre = preOf(code);
    if (!pre) return;

    var source = sourceOf(code);
    // The theme is part of the render key: a fence rendered before the preview
    // stylesheets applied (or under an old editor scheme) self-corrects on the
    // next pass instead of staying frozen in the wrong palette.
    var renderKey = hashContent(source) + "-" + theme;
    var pairId = pairIdOf(code);
    var host = findHost(pairId);

    // Up to date already: the render key matches what we last rendered AND the
    // host is still present. Nothing to do -- this is the common observer tick.
    if (host && code.getAttribute(HASH_ATTR) === renderKey) return;

    if (!host) {
      host = document.createElement("div");
      host.className = HOST_CLASS;
      host.setAttribute(PAIR_ATTR, pairId);
    }
    // Keep the host immediately after its <pre> for stable DOM/scroll order,
    // whether it was just created or had drifted (or been re-parented by the
    // incremental updater). insertBefore moves an already-attached node.
    // previousElementSibling (not previousSibling) so a whitespace text node
    // between <pre> and host doesn't make this perpetually true and fire a
    // harmless-but-wasteful insertBefore on every tick.
    if (host.previousElementSibling !== pre || host.parentNode !== pre.parentNode) {
      pre.parentNode.insertBefore(host, pre.nextSibling);
    }

    copyLineAttributes(pre, host);

    // Mirror core's palette on the paper card (.wiremark-dark in wiremark-ui.css
    // swaps the white card for core's dark paper).
    if (theme === "dark") host.classList.add(DARK_CLASS);
    else host.classList.remove(DARK_CLASS);

    try {
      var result = window.wiremark.render(source, { theme: theme });
      var svg = (result && result.svg) || "";
      host.innerHTML = svg + diagnosticsHtml(result && result.diagnostics);
      pre.classList.add(SOURCE_HIDDEN_CLASS); // hide source, keep it in the DOM
      host.classList.remove(ERROR_CLASS);
    } catch (e) {
      // Hard WiremarkError: show the message and keep the source visible so the
      // author can see and fix what they typed. The error wrapper markup is the
      // shared UI.errorHtml (one tested XSS-safe home); local fallback keeps the
      // same escaped shape if the shared module is somehow absent.
      pre.classList.remove(SOURCE_HIDDEN_CLASS);
      host.classList.add(ERROR_CLASS);
      var message = (e && e.message) || "Failed to render wireframe.";
      host.innerHTML = UI.errorHtml
        ? UI.errorHtml(message)
        : '<div class="wiremark-error-message">' + escapeHtml(message) + "</div>";
    }

    // Record what we rendered. Stored on the <code> (a node the incremental
    // updater owns and preserves), so a later edit that mutates the text but
    // leaves the element identity intact still re-renders on the next tick.
    code.setAttribute(HASH_ATTR, renderKey);
  }

  function renderAll() {
    // One detection per pass: every fence in a pass renders under one theme.
    var theme = detectTheme();
    var candidates = document.querySelectorAll(FENCE_SELECTOR);
    for (var i = 0; i < candidates.length; i++) {
      if (isWireframeFence(candidates[i])) renderFence(candidates[i], theme);
    }
  }

  // Coalesce bursts of mutations (the incremental updater fires many per edit)
  // into a single render pass on the next animation frame.
  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    var run = function () {
      scheduled = false;
      renderAll();
    };
    if (typeof window.requestAnimationFrame === "function") {
      window.requestAnimationFrame(run);
    } else {
      setTimeout(run, 16);
    }
  }

  // Styling (the load-bearing `pre.wiremark-source-hidden{display:none}` and
  // `.wiremark-host svg{...}` rules, plus the richer diagnostics/error look) now
  // lives in the shared wiremark-ui.css, injected as a <link> by the platform via
  // WiremarkPreviewExtension.styles. The glue no longer injects any <style>.

  function start() {
    renderAll();
    var observer = new MutationObserver(schedule);
    observer.observe(document.body, { childList: true, subtree: true });
    // The first pass can beat the preview's <link> stylesheets (they set the
    // body background detectTheme reads). Re-run once everything has loaded;
    // the theme-in-key makes it a no-op unless detection actually changed.
    if (document.readyState !== "complete") {
      window.addEventListener("load", schedule);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
