package dev.wiremark.intellij.editor

/**
 * Builds the self-contained HTML document loaded into the JCEF preview.
 *
 * `JBCefBrowser.loadHTML` serves the document from an opaque origin, so relative
 * `<script src=...>` cannot resolve. Instead we inline every asset (the wiremark
 * core bundle and the preview entry script) into a single document. The
 * resource I/O lives in [WiremarkPreviewResources]; the string assembly here is
 * pure so it can be unit-tested without the plugin classpath.
 */
object WiremarkPreviewHtml {
    const val BUNDLE_PLACEHOLDER = "<!-- WIREMARK_BUNDLE -->"
    const val UI_JS_PLACEHOLDER = "<!-- WIREMARK_UI_JS -->"
    const val PREVIEW_JS_PLACEHOLDER = "<!-- WIREMARK_PREVIEW_JS -->"

    /**
     * Placeholder inside the shell's `<style>` where the shared diagnostics/error
     * stylesheet (wiremark-ui.css) is inlined. A comment token, because the shell
     * serves from an opaque origin where a `<link>` could not resolve; the
     * markdown surface instead pulls the same file in via a `<link>` (getStyles()).
     */
    const val UI_CSS_PLACEHOLDER = "/* WIREMARK_UI_CSS */"

    /**
     * @param template      contents of wiremark-preview.html
     * @param bundleJs      contents of /web/wiremark.browser.js (IIFE, global `wiremark`)
     * @param uiJs          contents of /web/wiremark-ui.js (defines window.WiremarkUI)
     * @param previewJs     contents of wiremark-preview.js (defines window.renderWiremark)
     * @param uiCss         contents of /web/wiremark-ui.css (shared diagnostics/error styling)
     */
    fun build(
        template: String,
        bundleJs: String,
        uiJs: String,
        previewJs: String,
        uiCss: String,
    ): String {
        // A `var wiremark = ...` at non-module script top level is already a
        // global, but bridge defensively so window.wiremark is always set.
        val bundleScript = buildString {
            append("<script>\n")
            append(bundleJs)
            append("\n;try{if(typeof wiremark!=='undefined'){window.wiremark=window.wiremark||wiremark;}}catch(e){}\n")
            append("</script>")
        }
        // The shared UI helpers must define window.WiremarkUI before the preview
        // script runs, so it is inlined at its own placeholder, ahead of it.
        val uiScript = "<script>\n$uiJs\n</script>"
        val previewScript = "<script>\n$previewJs\n</script>"
        return template
            .replace(UI_CSS_PLACEHOLDER, uiCss)
            .replace(BUNDLE_PLACEHOLDER, bundleScript)
            .replace(UI_JS_PLACEHOLDER, uiScript)
            .replace(PREVIEW_JS_PLACEHOLDER, previewScript)
    }
}
