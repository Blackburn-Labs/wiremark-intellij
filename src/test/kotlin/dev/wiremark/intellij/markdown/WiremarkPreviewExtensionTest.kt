package dev.wiremark.intellij.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension

/**
 * Verifies Part A wiring against the real platform: the browser-preview extension
 * Provider is registered with the bundled Markdown plugin, and the extension
 * serves its scripts as resources.
 *
 * The test IDE includes the bundled Markdown plugin (declared in build.gradle.kts),
 * so [MarkdownBrowserPreviewExtension.Provider.EP] resolves here.
 */
class WiremarkPreviewExtensionTest : BasePlatformTestCase() {

    fun testProviderIsRegistered() {
        val providers = MarkdownBrowserPreviewExtension.Provider.EP.extensionList
        assertTrue(
            "WiremarkPreviewExtension.Provider should be registered via " +
                "org.intellij.markdown.browserPreviewExtensionProvider",
            providers.any { it is WiremarkPreviewExtension.Provider },
        )
    }

    fun testExtensionDeclaresScriptsInLoadOrder() {
        val extension = WiremarkPreviewExtension(panel = null)
        try {
            val scripts = extension.scripts
            assertEquals(
                "load order: core bundle, then the shared UI helper (window.WiremarkUI), " +
                    "then the glue -- the glue depends on both globals",
                listOf(
                    WiremarkPreviewExtension.WIREMARK_BUNDLE,
                    WiremarkPreviewExtension.WIREMARK_UI,
                    WiremarkPreviewExtension.WIREMARK_GLUE,
                ),
                scripts,
            )
            // The extension serves its own resources.
            assertSame(extension, extension.resourceProvider)
        } finally {
            extension.dispose()
        }
    }

    fun testExtensionDeclaresSharedStylesheet() {
        val extension = WiremarkPreviewExtension(panel = null)
        try {
            assertEquals(
                "the shared diagnostics/error stylesheet must be injected as a <link>",
                listOf(WiremarkPreviewExtension.WIREMARK_UI_CSS),
                extension.styles,
            )
        } finally {
            extension.dispose()
        }
    }

    fun testCanProvideOnlyDeclaredResources() {
        val extension = WiremarkPreviewExtension(panel = null)
        try {
            assertTrue(extension.canProvide(WiremarkPreviewExtension.WIREMARK_BUNDLE))
            assertTrue(extension.canProvide(WiremarkPreviewExtension.WIREMARK_UI))
            assertTrue(extension.canProvide(WiremarkPreviewExtension.WIREMARK_GLUE))
            assertTrue(extension.canProvide(WiremarkPreviewExtension.WIREMARK_UI_CSS))
            assertFalse(extension.canProvide("not-served.js"))
            assertFalse(extension.canProvide("/web/wiremark-glue.js"))
        } finally {
            extension.dispose()
        }
    }

    fun testGlueResourceResolves() {
        val extension = WiremarkPreviewExtension(panel = null)
        try {
            val resource = extension.loadResource(WiremarkPreviewExtension.WIREMARK_GLUE)
            assertNotNull("wiremark-glue.js must be packaged at /web/", resource)
            val text = String(resource!!.content, Charsets.UTF_8)
            assertTrue(
                "glue should reference the wiremark global render entry point",
                text.contains("wiremark.render"),
            )
        } finally {
            extension.dispose()
        }
    }

    fun testSharedUiResourcesResolve() {
        val extension = WiremarkPreviewExtension(panel = null)
        try {
            val js = extension.loadResource(WiremarkPreviewExtension.WIREMARK_UI)
            assertNotNull("wiremark-ui.js must be packaged at /web/", js)
            assertTrue(
                "shared helper must expose window.WiremarkUI",
                String(js!!.content, Charsets.UTF_8).contains("window.WiremarkUI"),
            )

            val css = extension.loadResource(WiremarkPreviewExtension.WIREMARK_UI_CSS)
            assertNotNull("wiremark-ui.css must be packaged at /web/", css)
            assertTrue(
                "shared stylesheet must define the diagnostics class",
                String(css!!.content, Charsets.UTF_8).contains(".wiremark-diagnostics"),
            )
        } finally {
            extension.dispose()
        }
    }

    fun testUnknownResourceReturnsNull() {
        val extension = WiremarkPreviewExtension(panel = null)
        try {
            assertNull(extension.loadResource("nope.js"))
        } finally {
            extension.dispose()
        }
    }
}
