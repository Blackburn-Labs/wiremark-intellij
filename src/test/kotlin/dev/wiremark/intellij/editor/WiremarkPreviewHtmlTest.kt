package dev.wiremark.intellij.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiremarkPreviewHtmlTest {

    private val template =
        """
        <html><body>
          <div id="wiremark-root"></div>
          ${WiremarkPreviewHtml.BUNDLE_PLACEHOLDER}
          ${WiremarkPreviewHtml.PREVIEW_JS_PLACEHOLDER}
        </body></html>
        """.trimIndent()

    @Test
    fun `placeholders are replaced with script tags`() {
        val html = WiremarkPreviewHtml.build(template, "var wiremark={};", "window.renderWiremark=function(){};")
        assertFalse("bundle placeholder must be gone", html.contains(WiremarkPreviewHtml.BUNDLE_PLACEHOLDER))
        assertFalse("preview placeholder must be gone", html.contains(WiremarkPreviewHtml.PREVIEW_JS_PLACEHOLDER))
        assertTrue("bundle content inlined", html.contains("var wiremark={};"))
        assertTrue("preview content inlined", html.contains("window.renderWiremark=function(){};"))
    }

    @Test
    fun `bundle is bridged to window dot wiremark`() {
        val html = WiremarkPreviewHtml.build(template, "var wiremark={};", "noop();")
        assertTrue(
            "must bridge the global to window.wiremark",
            html.contains("window.wiremark=window.wiremark||wiremark"),
        )
    }

    @Test
    fun `bundle script precedes the preview script`() {
        val html = WiremarkPreviewHtml.build(template, "BUNDLE_MARKER", "PREVIEW_MARKER")
        assertTrue(
            "the core bundle must be inlined before the preview entry script",
            html.indexOf("BUNDLE_MARKER") < html.indexOf("PREVIEW_MARKER"),
        )
    }
}
