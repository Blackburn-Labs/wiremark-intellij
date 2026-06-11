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

  function el(id) {
    return document.getElementById(id);
  }

  // Extract a human-readable message from anything render() throws. A
  // WiremarkError (dev1 contract) always has .message; for an unexpected
  // throwable we avoid "[object Object]" by falling back to a generic notice.
  function messageOf(e) {
    if (e && typeof e.message === "string" && e.message) return e.message;
    if (typeof e === "string" && e) return e;
    return "Wiremark failed to render this source.";
  }

  function showError(message) {
    var box = el("wiremark-error");
    if (!box) return;
    box.textContent = message;
    box.classList.add("visible");
  }

  function clearError() {
    var box = el("wiremark-error");
    if (!box) return;
    box.textContent = "";
    box.classList.remove("visible");
  }

  function hidePlaceholder() {
    var ph = el("wiremark-placeholder");
    if (ph) ph.style.display = "none";
  }

  // Minimal, structural diagnostics rendering. dev4 replaces the markup; the
  // container id and the { severity, message, loc } shape are the stable parts.
  function renderDiagnostics(diagnostics) {
    var host = el("wiremark-diagnostics");
    if (!host) return;
    host.innerHTML = "";
    if (!diagnostics || !diagnostics.length) return;
    var list = document.createElement("ul");
    list.className = "wiremark-diagnostic-list";
    diagnostics.forEach(function (d) {
      var item = document.createElement("li");
      item.className = "wiremark-diagnostic wiremark-diagnostic-" + (d.severity || "warning");
      var where = d.loc && d.loc.line ? " (line " + d.loc.line + ")" : "";
      item.textContent = (d.severity || "warning") + ": " + d.message + where;
      list.appendChild(item);
    });
    host.appendChild(list);
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
