package dev.wiremark.intellij.markdown

import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider

/**
 * Injects the wiremark browser bundle and glue script into the Markdown preview
 * (a JCEF panel), so fenced ```wireframe / ```wiremark blocks render as SVGs the
 * way Mermaid diagrams do.
 *
 * Registered through `org.intellij.markdown.browserPreviewExtensionProvider`
 * (see wiremark-markdown.xml). This is the same mechanism the platform's own
 * preview extensions (e.g. ProcessLinksExtension) use; the extension serves its
 * own scripts as a [ResourceProvider].
 *
 * The two served scripts, in load order:
 *  1. [WIREMARK_BUNDLE] - dev1's esbuild IIFE bundle of @wiremark/core, exposing
 *     the global `wiremark` with a synchronous `render(src) -> { svg, diagnostics }`.
 *     Generated at build time into the `/web` resource root; never committed.
 *  2. [WIREMARK_GLUE] - hand-written glue that observes the preview DOM and swaps
 *     wireframe fences for rendered SVGs (src/main/resources/web/wiremark-glue.js).
 *
 * Resource names are classpath-absolute (leading `/web/...`) so
 * [ResourceProvider.loadInternalResource] -> `Class.getResourceAsStream` resolves
 * them from the classpath root rather than relative to this class's package.
 */
internal class WiremarkPreviewExtension(
    // The panel is unused: this extension only injects static scripts and serves
    // its own resources. Nullable so tests can construct it without a live JCEF
    // panel; the Provider below always passes the real (non-null) panel.
    @Suppress("unused") private val panel: MarkdownHtmlPanel?,
) : MarkdownBrowserPreviewExtension, ResourceProvider {

    override val scripts: List<String> = listOf(WIREMARK_BUNDLE, WIREMARK_GLUE)

    override val resourceProvider: ResourceProvider = this

    override fun canProvide(resourceName: String): Boolean = resourceName in scripts

    override fun loadResource(resourceName: String): ResourceProvider.Resource? {
        if (resourceName !in scripts) return null
        // Content-Type is left null on purpose: the preview server guesses it from
        // the ".js" suffix (application/javascript; charset=utf-8).
        return ResourceProvider.loadInternalResource(
            WiremarkPreviewExtension::class.java,
            "/web/$resourceName",
            null,
        )
    }

    override fun dispose() = Unit

    class Provider : MarkdownBrowserPreviewExtension.Provider {
        override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension =
            WiremarkPreviewExtension(panel)
    }

    companion object {
        const val WIREMARK_BUNDLE = "wiremark.browser.js"
        const val WIREMARK_GLUE = "wiremark-glue.js"
    }
}
