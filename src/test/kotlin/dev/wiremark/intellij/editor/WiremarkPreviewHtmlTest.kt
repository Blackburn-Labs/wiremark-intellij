package dev.wiremark.intellij.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiremarkPreviewHtmlTest {

    private val template =
        """
        <html><head><style>
          ${WiremarkPreviewHtml.UI_CSS_PLACEHOLDER}
        </style></head><body>
          <div id="wiremark-root"></div>
          ${WiremarkPreviewHtml.BUNDLE_PLACEHOLDER}
          ${WiremarkPreviewHtml.UI_JS_PLACEHOLDER}
          ${WiremarkPreviewHtml.PREVIEW_JS_PLACEHOLDER}
        </body></html>
        """.trimIndent()

    private fun build(
        bundleJs: String = "var wiremark={};",
        uiJs: String = "window.WiremarkUI={};",
        previewJs: String = "window.renderWiremark=function(){};",
        uiCss: String = ".wiremark-host{}",
    ): String = WiremarkPreviewHtml.build(template, bundleJs, uiJs, previewJs, uiCss)

    @Test
    fun `placeholders are replaced with inlined content`() {
        val html = build()
        assertFalse("bundle placeholder must be gone", html.contains(WiremarkPreviewHtml.BUNDLE_PLACEHOLDER))
        assertFalse("ui-js placeholder must be gone", html.contains(WiremarkPreviewHtml.UI_JS_PLACEHOLDER))
        assertFalse("preview placeholder must be gone", html.contains(WiremarkPreviewHtml.PREVIEW_JS_PLACEHOLDER))
        assertFalse("ui-css placeholder must be gone", html.contains(WiremarkPreviewHtml.UI_CSS_PLACEHOLDER))
        assertTrue("bundle content inlined", html.contains("var wiremark={};"))
        assertTrue("shared ui js inlined", html.contains("window.WiremarkUI={};"))
        assertTrue("preview content inlined", html.contains("window.renderWiremark=function(){};"))
        assertTrue("shared ui css inlined", html.contains(".wiremark-host{}"))
    }

    @Test
    fun `bundle is bridged to window dot wiremark`() {
        val html = build(previewJs = "noop();")
        assertTrue(
            "must bridge the global to window.wiremark",
            html.contains("window.wiremark=window.wiremark||wiremark"),
        )
    }

    @Test
    fun `scripts are inlined in dependency order bundle then ui then preview`() {
        val html = build(
            bundleJs = "BUNDLE_MARKER",
            uiJs = "UI_MARKER",
            previewJs = "PREVIEW_MARKER",
        )
        val bundle = html.indexOf("BUNDLE_MARKER")
        val ui = html.indexOf("UI_MARKER")
        val preview = html.indexOf("PREVIEW_MARKER")
        // The shared helper (window.WiremarkUI) must be defined before the preview
        // entry uses it; the core bundle stays first.
        assertTrue("bundle before shared ui helper", bundle < ui)
        assertTrue("shared ui helper before preview entry", ui < preview)
    }

    @Test
    fun `ui css is inlined into the style block before the scripts`() {
        val html = build(uiCss = "CSS_MARKER", bundleJs = "BUNDLE_MARKER")
        // The CSS lands in <head><style>, ahead of the body scripts.
        assertTrue("ui css before bundle script", html.indexOf("CSS_MARKER") < html.indexOf("BUNDLE_MARKER"))
    }
}
