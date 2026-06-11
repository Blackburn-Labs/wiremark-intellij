package dev.wiremark.intellij.editor

import com.intellij.openapi.diagnostic.logger

/**
 * Loads the preview assets from the plugin classpath and assembles the inlined
 * HTML document. Kept separate from the editor so a missing bundle degrades to a
 * clear message instead of a blank panel or an exception.
 */
object WiremarkPreviewResources {
    private val LOG = logger<WiremarkPreviewResources>()

    private const val TEMPLATE_PATH = "/web/wiremark-preview.html"
    private const val PREVIEW_JS_PATH = "/web/wiremark-preview.js"

    /** Runtime classpath location of dev1's generated browser bundle. */
    private const val BUNDLE_PATH = "/web/wiremark.browser.js"

    private fun readResource(path: String): String? =
        WiremarkPreviewResources::class.java.getResourceAsStream(path)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    /**
     * The full inlined preview HTML, or a self-contained fallback document if a
     * required resource is missing (so the editor still opens).
     */
    fun buildPreviewHtml(): String {
        val template = readResource(TEMPLATE_PATH)
        val previewJs = readResource(PREVIEW_JS_PATH)
        val bundleJs = readResource(BUNDLE_PATH)

        if (template == null || previewJs == null || bundleJs == null) {
            LOG.warn(
                "Wiremark preview assets missing " +
                    "(template=${template != null}, previewJs=${previewJs != null}, bundle=${bundleJs != null}); " +
                    "showing fallback panel.",
            )
            return fallbackHtml(bundleMissing = bundleJs == null)
        }
        return WiremarkPreviewHtml.build(template, bundleJs, previewJs)
    }

    private fun fallbackHtml(bundleMissing: Boolean): String {
        val detail =
            if (bundleMissing) {
                "The wiremark renderer bundle is not on the plugin classpath."
            } else {
                "A wiremark preview resource failed to load."
            }
        return """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"></head>
            <body style="font-family: sans-serif; padding: 16px; color: #b23535;">
              <p>Wiremark preview unavailable.</p>
              <p style="color:#777;font-size:12px;">$detail</p>
            </body></html>
        """.trimIndent()
    }
}
