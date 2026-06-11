// Shared diagnostics / error formatting helpers for BOTH wiremark preview
// surfaces (dev4, task #4). Exposed as `window.WiremarkUI` so the markdown glue
// (wiremark-glue.js) and the split-editor preview (wiremark-preview.js) format
// diagnostics and escape text identically -- one source of truth.
//
// Load order matters: this script must run BEFORE its consumers.
//   - Markdown preview: listed first in WiremarkPreviewExtension.scripts.
//   - Split preview: inlined before wiremark-preview.js by WiremarkPreviewHtml.
//
// No DOM or platform assumptions: pure string helpers, safe to load anywhere.
(function () {
  "use strict";

  // HTML-escape arbitrary text for safe interpolation into innerHTML. Covers the
  // five characters that matter inside element content and double-quoted
  // attributes; messages from wiremark may contain user source (e.g. an offending
  // token), so this is the XSS boundary for both surfaces.
  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // Normalize one diagnostic from wiremark.render().diagnostics into a stable,
  // already-escaped shape the surfaces render. Contract (pinned against
  // @wiremark/core@0.0.4): { severity: 'warning'|'error', message: string,
  // loc?: { line: number, col?: number } }. Anything other than 'error' is
  // treated as a warning (the safe default for an unknown/missing severity).
  function normalizeDiagnostic(d) {
    var severity = d && d.severity === "error" ? "error" : "warning";
    // Coerce loc.line to a number before interpolating it into the "(line N)"
    // suffix. Core emits it as a number, but this value lands in innerHTML on
    // both surfaces, so we must not trust it: a non-numeric loc.line (a future
    // core version, a malformed/hand-fed diagnostic, or the task #6 icon-src
    // bridge feeding this path) would otherwise inject raw HTML. Anything
    // non-numeric is dropped -- escapeHtml only guards message, not this suffix.
    var line = d && d.loc && typeof d.loc.line === "number" ? d.loc.line : null;
    var where = line !== null ? " (line " + line + ")" : "";
    return {
      severity: severity,
      // The visible text: message plus an optional "(line N)" suffix. Escaped.
      text: escapeHtml((d && d.message) || "") + where,
    };
  }

  // Build the soft-diagnostics footer markup (an empty string when there are
  // none, so callers can append unconditionally). Class names match dev3's
  // pinned glue contract and the shared stylesheet:
  //   <ul class="wiremark-diagnostics">
  //     <li class="wiremark-diagnostic-warning|wiremark-diagnostic-error">TEXT</li>
  //   </ul>
  function diagnosticsHtml(diagnostics) {
    if (!diagnostics || !diagnostics.length) return "";
    var items = "";
    for (var i = 0; i < diagnostics.length; i++) {
      var n = normalizeDiagnostic(diagnostics[i]);
      items +=
        '<li class="wiremark-diagnostic-' + n.severity + '">' + n.text + "</li>";
    }
    return '<ul class="wiremark-diagnostics">' + items + "</ul>";
  }

  // Build the hard-error banner markup: an escaped message wrapped in
  // .wiremark-error-message (the shared CSS adds the "Error:" lead-in via
  // ::before). Single home for the one XSS-sensitive error wrapper both surfaces
  // emit -- the markdown host-as-error-banner and the split-preview #wiremark-error.
  function errorHtml(message) {
    return '<div class="wiremark-error-message">' + escapeHtml(message) + "</div>";
  }

  // Extract a human-readable message from anything render() throws. A
  // WiremarkError (dev1 contract) is NOT a standard Error -- `e instanceof Error`
  // is false -- but it always carries a string `.message`. We read that directly
  // and only fall back for a truly unexpected throwable (never "[object Object]").
  function messageOf(e, fallback) {
    if (e && typeof e.message === "string" && e.message) return e.message;
    if (typeof e === "string" && e) return e;
    return fallback || "Wiremark failed to render this source.";
  }

  window.WiremarkUI = {
    escapeHtml: escapeHtml,
    diagnosticsHtml: diagnosticsHtml,
    normalizeDiagnostic: normalizeDiagnostic,
    errorHtml: errorHtml,
    messageOf: messageOf,
  };
})();
