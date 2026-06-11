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
    const val PREVIEW_JS_PLACEHOLDER = "<!-- WIREMARK_PREVIEW_JS -->"

    /**
     * @param template      contents of wiremark-preview.html
     * @param bundleJs      contents of /web/wiremark.browser.js (IIFE, global `wiremark`)
     * @param previewJs     contents of wiremark-preview.js (defines window.renderWiremark)
     */
    fun build(template: String, bundleJs: String, previewJs: String): String {
        // A `var wiremark = ...` at non-module script top level is already a
        // global, but bridge defensively so window.wiremark is always set.
        val bundleScript = buildString {
            append("<script>\n")
            append(bundleJs)
            append("\n;try{if(typeof wiremark!=='undefined'){window.wiremark=window.wiremark||wiremark;}}catch(e){}\n")
            append("</script>")
        }
        val previewScript = "<script>\n$previewJs\n</script>"
        return template
            .replace(BUNDLE_PLACEHOLDER, bundleScript)
            .replace(PREVIEW_JS_PLACEHOLDER, previewScript)
    }
}
