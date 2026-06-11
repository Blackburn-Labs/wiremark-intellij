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
 * The three served scripts, in load order:
 *  1. [WIREMARK_BUNDLE] - dev1's esbuild IIFE bundle of @wiremark/core, exposing
 *     the global `wiremark` with a synchronous `render(src) -> { svg, diagnostics }`.
 *     Generated at build time into the `/web` resource root; never committed.
 *  2. [WIREMARK_UI] - shared diagnostics/error formatting helpers (dev4), exposing
 *     `window.WiremarkUI`. Loaded before the glue so it can use those helpers.
 *  3. [WIREMARK_GLUE] - hand-written glue that observes the preview DOM and swaps
 *     wireframe fences for rendered SVGs (src/main/resources/web/wiremark-glue.js).
 *
 * Plus one served stylesheet, injected as a <link> by the platform:
 *  - [WIREMARK_UI_CSS] - shared diagnostics/error styling (dev4), the single
 *    source of truth shared with the *.wiremark split-editor preview.
 *
 * Resource names are classpath-absolute (leading `/web/...`) so
 * [ResourceProvider.loadInternalResource] -> `Class.getResourceAsStream` resolves
 * them from the classpath root rather than relative to this class's package.
 *
 * Icons-block `src=` entries are NOT bridged on this surface (task #6 implements
 * the bridge only for the *.wiremark split editor). This EP injects static
 * scripts and serves static resources; it has no per-document hook that knows the
 * source .md file's path, which is required to pre-read `src=` icons relative to
 * the document (the way the split editor's WiremarkPreviewFileEditor does it).
 * The glue therefore calls `wiremark.render(source)` with no `loadIcon`, so any
 * `src=` icon degrades to core's placeholder glyph + a soft diagnostic -- the
 * same graceful fallback core gives when no host loader is supplied. Bridging
 * here would need a different injection mechanism and is deferred (see PLAN.md
 * "Icons with src=").
 */
internal class WiremarkPreviewExtension(
    // The panel is unused: this extension only injects static scripts and serves
    // its own resources. Nullable so tests can construct it without a live JCEF
    // panel; the Provider below always passes the real (non-null) panel.
    @Suppress("unused") private val panel: MarkdownHtmlPanel?,
) : MarkdownBrowserPreviewExtension, ResourceProvider {

    override val scripts: List<String> = listOf(WIREMARK_BUNDLE, WIREMARK_UI, WIREMARK_GLUE)

    override val styles: List<String> = listOf(WIREMARK_UI_CSS)

    override val resourceProvider: ResourceProvider = this

    // Everything we declare in scripts + styles is served from our own resources.
    private val servedResources: Set<String> = (scripts + styles).toSet()

    override fun canProvide(resourceName: String): Boolean = resourceName in servedResources

    override fun loadResource(resourceName: String): ResourceProvider.Resource? {
        if (resourceName !in servedResources) return null
        // Content-Type is left null on purpose: the preview server guesses it from
        // the file suffix (".js" -> application/javascript, ".css" -> text/css).
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
        const val WIREMARK_UI = "wiremark-ui.js"
        const val WIREMARK_GLUE = "wiremark-glue.js"
        const val WIREMARK_UI_CSS = "wiremark-ui.css"
    }
}
