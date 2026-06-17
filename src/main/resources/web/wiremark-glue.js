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
//
// Document-scope backgrounds: core resolves `background=#id` (SPEC ss.5.1.1)
// within a single source string -- but a markdown document spreads its frames
// across several fences, so a fence's `background=#id` may target a frame
// declared in a DIFFERENT fence. When any fence uses such a reference we
// `wiremark.parse()` every fence once, index their frames by #id, and render the
// referencing fence from a merged Document: its own frames over the referenced
// frames pulled in as invisible backgrounds (buildDocContext /
// resolveBackgroundDeps below). A self-contained document never parses and
// renders exactly as it did before.

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

  // A frame-level cross-frame background reference is spelled `background=#id`
  // (the lexer makes that one keyed token; the tint enum `background=hatch`
  // carries no `#`, and `denseBackground` no `=#`). We use this as a cheap
  // textual gate: when NO fence in the document contains one, the entire
  // document-scope machinery below is skipped and every fence renders standalone
  // exactly as before -- same SVG, same cost, no parsing.
  var FRAME_BG_REF = /background\s*=\s*#/;

  // A `visible:false` shallow copy of a parsed Frame: it composes UNDERNEATH a
  // referencing frame as a background but is never drawn standalone (core omits
  // invisible frames from standalone output -- see layout.js/render.js). props is
  // copied so the original parsed frame (shared with the library/parse cache) is
  // never mutated; children are shared read-only (render never mutates them).
  function invisibleFrame(frame) {
    return Object.assign({}, frame, {
      props: Object.assign({}, frame.props, { visible: false }),
    });
  }

  // Parse every wireframe fence once and index its frames by #id, so a fence can
  // compose a `background=#id` whose target lives in ANOTHER fence of the same
  // markdown document. Core scopes background resolution to "the document" (SPEC
  // ss.5.1.1); for a .md the document spans all of its wireframe fences, so we
  // resolve across them here -- the one thing core (which sees a single fence's
  // source at a time) cannot do itself. Returns:
  //   library: Map<frameId, { frame, sourceHash }> -- first declaration in DOM
  //            order wins, mirroring core's own first-wins duplicate-id rule.
  //   docs:    Map<sourceHash, { doc, error }>      -- parse cache, reused by
  //            resolveBackgroundDeps so each fence parses at most once per pass.
  // Built only when at least one fence references a cross-frame background.
  function buildDocContext(fences) {
    var docs = new Map();
    var library = new Map();
    for (var i = 0; i < fences.length; i++) {
      var f = fences[i];
      var entry = docs.get(f.hash);
      if (!entry) {
        // A fence with a hard parse error contributes no library frames, but
        // must not abort the others -- the rest of the document still composes.
        try {
          entry = { doc: window.wiremark.parse(f.source), error: null };
        } catch (e) {
          entry = { doc: null, error: e };
        }
        docs.set(f.hash, entry);
      }
      if (!entry.doc) continue;
      var frames = entry.doc.frames || [];
      for (var j = 0; j < frames.length; j++) {
        var fr = frames[j];
        if (fr.id && !library.has(fr.id)) library.set(fr.id, { frame: fr, sourceHash: f.hash });
      }
    }
    return { docs: docs, library: library };
  }

  // Work out which OTHER-fence frames a fence needs as invisible backgrounds.
  // Walks each of the fence's own frames' `background=#id` chain; any target not
  // defined within this fence is pulled from the document library as an invisible
  // copy. The walk is transitive (a pulled background may have its own
  // background) and an `anchor=#el` resolves -- in core -- within that chain, so
  // pulling the background frames is sufficient to satisfy the anchor too.
  // Returns null when the fence did not parse (the caller then renders it
  // standalone, which surfaces the parse error in the usual banner).
  //   sig -- a stable signature of every cross-fence dependency (`id:sourceHash`,
  //          or `id:?` when the target is missing document-wide). Folded into the
  //          render key so the fence re-renders when a referenced fence's text
  //          CHANGES, APPEARS, or DISAPPEARS -- not only when its own text does.
  function resolveBackgroundDeps(hash, ctx) {
    var entry = ctx.docs.get(hash);
    if (!entry || !entry.doc) return null;
    var ownFrames = entry.doc.frames || [];
    var ownIds = new Set();
    for (var i = 0; i < ownFrames.length; i++) if (ownFrames[i].id) ownIds.add(ownFrames[i].id);

    var pulled = [];
    var sig = [];
    var seen = new Set(); // background ids already considered (terminates cycles)
    var queue = ownFrames.slice();
    while (queue.length) {
      var fr = queue.shift();
      var bg = fr.props && fr.props.background;
      if (!bg || ownIds.has(bg) || seen.has(bg)) continue;
      seen.add(bg);
      var lib = ctx.library.get(bg);
      if (!lib) { sig.push(bg + ":?"); continue; } // missing document-wide -> core warns
      sig.push(bg + ":" + lib.sourceHash);
      pulled.push(invisibleFrame(lib.frame));
      queue.push(lib.frame); // follow ITS background, transitively
    }
    return { doc: entry.doc, pulled: pulled, sig: sig.join(",") };
  }

  // Render (or re-render) a single fence under [theme]. Idempotent: safe to
  // call every tick. [ctx] is the document frame library (or null when no fence
  // uses a cross-frame background); [hash] is the precomputed content hash.
  function renderFence(code, source, hash, theme, ctx) {
    var pre = preOf(code);
    if (!pre) return;

    // Document-scope background composition: engaged only when a context exists
    // (some fence in the doc uses a cross-frame background) AND this fence
    // actually references one. Otherwise the legacy standalone path renders
    // byte-for-byte as before -- a self-contained fence is never parsed twice.
    var dep = ctx && FRAME_BG_REF.test(source) ? resolveBackgroundDeps(hash, ctx) : null;
    var merged = dep && dep.pulled.length
      ? { frames: dep.pulled.concat(dep.doc.frames), diagnostics: (dep.doc.diagnostics || []).slice(), flow: dep.doc.flow }
      : null;

    // The theme is part of the render key: a fence rendered before the preview
    // stylesheets applied (or under an old editor scheme) self-corrects on the
    // next pass instead of staying frozen in the wrong palette. The bg(...)
    // suffix carries the cross-fence dependency signature so a referenced
    // background's edit/appearance/removal re-renders this fence too.
    var renderKey = hash + "-" + theme + (dep && dep.sig ? "-bg(" + dep.sig + ")" : "");
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
      // A merged Document (this fence's frames over invisible backgrounds pulled
      // from other fences) renders document-scoped; otherwise the raw source
      // renders standalone, identical to the single-fence path.
      var result = merged
        ? window.wiremark.render(merged, { theme: theme })
        : window.wiremark.render(source, { theme: theme });
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
    // Collect this document's wireframe fences in DOM order, each with its
    // content hash, and note whether any composes a cross-frame background.
    var fences = [];
    var anyCrossRef = false;
    for (var i = 0; i < candidates.length; i++) {
      var code = candidates[i];
      if (!isWireframeFence(code)) continue;
      var source = sourceOf(code);
      fences.push({ code: code, source: source, hash: hashContent(source) });
      if (FRAME_BG_REF.test(source)) anyCrossRef = true;
    }
    // The document frame library is built only when some fence composes a
    // cross-frame background AND the bundle exposes parse(). Without it every
    // fence renders standalone, exactly as before (and a missing background
    // degrades to core's soft "not found" warning, as it always has).
    var ctx =
      anyCrossRef && typeof window.wiremark.parse === "function"
        ? buildDocContext(fences)
        : null;
    for (var k = 0; k < fences.length; k++) {
      renderFence(fences[k].code, fences[k].source, fences[k].hash, theme, ctx);
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
