// Split-editor preview entry point for *.wiremark files (dev2 owns this file).
//
// Contract with the wiremark core bundle (dev1): a global `wiremark` object with
//   wiremark.render(src) -> { svg, diagnostics }   (throws WiremarkError on a
// hard parse error). The Kotlin side calls window.renderWiremark(src) over JCEF
// after debouncing document edits.
//
// Error model: a thrown WiremarkError shows a banner over the LAST GOOD SVG
// (the panel never goes blank mid-edit). Soft diagnostics render into
// #wiremark-diagnostics. dev4 will polish the diagnostics/error UI; this file
// keeps the structure (ids, the last-good-svg behaviour) it needs.
(function () {
  "use strict";

  // Shared formatting helpers (wiremark-ui.js, inlined before this script by
  // WiremarkPreviewHtml): escapeHtml, the diagnostics-footer builder, and
  // messageOf. Local fallbacks keep the preview functional if the shared module
  // somehow failed to load.
  var UI = window.WiremarkUI || {};

  function el(id) {
    return document.getElementById(id);
  }

  // Extract a human-readable message from anything render() throws. A
  // WiremarkError (dev1 contract) always has .message; for an unexpected
  // throwable we avoid "[object Object]" by falling back to a generic notice.
  function messageOf(e) {
    if (UI.messageOf) return UI.messageOf(e, "Wiremark failed to render this source.");
    if (e && typeof e.message === "string" && e.message) return e.message;
    if (typeof e === "string" && e) return e;
    return "Wiremark failed to render this source.";
  }

  function showError(message) {
    var box = el("wiremark-error");
    if (!box) return;
    // Wrap in .wiremark-error-message so this banner matches the markdown
    // surface (the shared CSS adds the "Error:" lead-in via ::before). Uses the
    // shared UI.errorHtml (one tested XSS-safe home); the local fallback keeps
    // the same escaped shape if the shared module is somehow absent.
    if (UI.errorHtml) {
      box.innerHTML = UI.errorHtml(message);
    } else {
      var escape = function (s) {
        return String(s)
          .replace(/&/g, "&amp;")
          .replace(/</g, "&lt;")
          .replace(/>/g, "&gt;")
          .replace(/"/g, "&quot;")
          .replace(/'/g, "&#39;");
      };
      box.innerHTML = '<div class="wiremark-error-message">' + escape(message) + "</div>";
    }
    box.classList.add("visible");
  }

  function clearError() {
    var box = el("wiremark-error");
    if (!box) return;
    box.innerHTML = "";
    box.classList.remove("visible");
  }

  function hidePlaceholder() {
    var ph = el("wiremark-placeholder");
    if (ph) ph.style.display = "none";
  }

  // Render the soft-diagnostics footer into #wiremark-diagnostics, using the
  // shared builder so this surface and the markdown preview emit identical
  // `<ul class="wiremark-diagnostics">...` markup (the builder escapes messages).
  // Cleared to empty when there are none, so the container's :empty rule hides it.
  function renderDiagnostics(diagnostics) {
    var host = el("wiremark-diagnostics");
    if (!host) return;
    host.innerHTML = UI.diagnosticsHtml ? UI.diagnosticsHtml(diagnostics) : "";
  }

  // window.renderWiremark(src): called from Kotlin via executeJavaScript.
  window.renderWiremark = function (src) {
    hidePlaceholder();

    if (typeof window.wiremark === "undefined" || typeof window.wiremark.render !== "function") {
      showError("wiremark core bundle failed to load.");
      return;
    }

    var result;
    try {
      result = window.wiremark.render(src);
    } catch (e) {
      // Hard parse failure: keep the last good SVG, surface the message.
      // NOTE (dev1 contract): a thrown WiremarkError is NOT a standard Error --
      // `e instanceof Error` is false. It is identified by e.name ===
      // 'WiremarkError' (or `e instanceof window.wiremark.WiremarkError`) and
      // carries e.message. So we never branch on `instanceof Error`; we read
      // e.message directly and only fall back for a truly unexpected throwable.
      showError(messageOf(e));
      return;
    }

    clearError();
    var host = el("wiremark-host");
    if (host) host.innerHTML = result && result.svg ? result.svg : "";
    renderDiagnostics(result && result.diagnostics);
  };
})();
