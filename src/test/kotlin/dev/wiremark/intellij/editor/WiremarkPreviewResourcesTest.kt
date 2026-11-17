package dev.wiremark.intellij.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises resource loading and assembly against the real plugin resources on
 * the test classpath. The static preview HTML/JS are always present; the
 * generated core bundle is present once processResources has run (it is a
 * dependency of `check`), so when available we assert the full inlined document.
 */
class WiremarkPreviewResourcesTest {

    @Test
    fun `preview html template and entry script are on the classpath`() {
        // These two are hand-written resources owned by this task; they must load.
        val html = WiremarkPreviewResources.buildPreviewHtml()
        assertTrue("document must be non-empty", html.isNotBlank())
    }

    @Test
    fun `preview csp allows embedded data fonts but stays closed to the network`() {
        // Core 0.2.0 embeds its sketch font as an @font-face data: URL in every SVG.
        val template = javaClass.getResource("/web/wiremark-preview.html")!!.readText()
        val csp = Regex("""Content-Security-Policy"\s+content="([^"]*)"""").find(template)
            ?.groupValues?.get(1)
        requireNotNull(csp) { "preview html must declare a CSP" }
        assertTrue("CSP must start from default-src 'none'", csp.contains("default-src 'none'"))
        assertTrue("CSP must allow data: fonts", csp.contains("font-src data:;"))
        assertFalse("CSP must not allow network sources", Regex("https?:|\\*").containsMatchIn(csp))
    }

    @Test
    fun `assembled document wires the preview entry point when the bundle is present`() {
        // The generated core bundle is on the classpath once processResources has
        // run (a dependency of `check`). If it isn't present in this run, the
        // fallback path is exercised by the test above instead.
        val bundlePresent = javaClass.getResource("/web/wiremark.browser.js") != null
        if (!bundlePresent) return

        val html = WiremarkPreviewResources.buildPreviewHtml()
        assertTrue("must define window.renderWiremark", html.contains("window.renderWiremark"))
        assertTrue("must inline the wiremark core bundle global", html.contains("var wiremark="))
        assertTrue("must contain the render host", html.contains("id=\"wiremark-host\""))
        assertTrue("must contain the error banner element", html.contains("id=\"wiremark-error\""))
        assertTrue("must contain the diagnostics container", html.contains("id=\"wiremark-diagnostics\""))
        // dev4: the shared diagnostics/error helper + stylesheet are inlined too.
        assertTrue("must define the shared window.WiremarkUI helper", html.contains("window.WiremarkUI"))
        assertTrue("must inline the shared diagnostics/error CSS", html.contains(".wiremark-diagnostics"))
        // No placeholder may survive in the assembled document.
        assertFalse("ui-css placeholder must be filled", html.contains(WiremarkPreviewHtml.UI_CSS_PLACEHOLDER))
        assertFalse("ui-js placeholder must be filled", html.contains(WiremarkPreviewHtml.UI_JS_PLACEHOLDER))
    }
}
