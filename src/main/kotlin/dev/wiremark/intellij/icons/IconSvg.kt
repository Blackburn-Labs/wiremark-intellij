package dev.wiremark.intellij.icons

/**
 * A renderable icon extracted from an SVG file: the inner markup of its `<svg>`
 * element (`body`) and the square grid size that markup targets (`viewBox`).
 *
 * This is the exact shape core's `loadIcon` callback may return -- core wraps it
 * back into `{ body, viewBox }` and draw.js embeds `body` VERBATIM into the SVG
 * it generates (`/tmp/package/src/draw.js` iconBody: raw inner markup when it
 * starts with `<`, else `<path d="${body}"/>`). That generated SVG is set via
 * innerHTML on the JCEF preview, so [body] is an XSS boundary -- see [IconSvg].
 */
data class IconArt(val body: String, val viewBox: Double)

/**
 * Extracts sanitized icon artwork from SVG file text. It began as a port of the
 * wiremark CLI's `iconFromSvg` (`/tmp/package/src/cli.js`) and has since been
 * hardened past it to close bypasses that matter only for a LIVE-DOM embedder.
 *
 * THREAT MODEL. An `Icons`-block `src=` file is supply-chain-untrusted input
 * (it ships in someone's project / repo). Its `<svg>` inner markup becomes the
 * icon `body`, which core's draw.js embeds VERBATIM into the SVG it generates,
 * and the preview sets that SVG via innerHTML in the IDE's embedded Chromium
 * (JCEF). So `body` is a live XSS boundary. The upstream CLI writes its result
 * to a static `.svg` file, so its threat model is lower and its sanitizer is
 * looser; this surface needs more. Sanitizing HERE (Kotlin), not in the JS,
 * keeps one trusted implementation, unit-testable without a live Chromium, and
 * leaves the JS `loadIcon` a trivial already-safe map lookup.
 *
 * APPROACH (v1): a regex DENYLIST -- remove known-dangerous elements and
 * attributes. It is deliberately conservative (e.g. it drops SMIL animation and
 * all `style=`, which static wireframe icons never need). Each rule was verified
 * empirically in JCEF/Chromium against the bypass corpus in IconSvgTest, not by
 * regex reasoning alone. Every element/attribute rule applies the SAME defenses
 * uniformly: a `[\s/]` leading boundary (a solidus is an attribute separator in
 * the HTML tokenizer) and, for elements, tolerance of an optional namespace
 * prefix -- a gap in any one rule reopens the hole the others close.
 *
 * STOP CONDITION (not "someday" -- a trigger): a regex denylist is inherently
 * best-effort. If ANY further sanitizer-bypass class is found after v1 ships, do
 * NOT keep extending this denylist -- switch to a structural XML ALLOWLIST parser
 * (parse the SVG, keep only a vetted element/attribute set; e.g. a real XML
 * parser or DOMPurify-style profile). Naming that trigger is what keeps this from
 * becoming permanent whack-a-mole. Until it fires, any change to the rules below
 * MUST re-verify the IconSvgTest corpus (and ideally re-probe in a live DOM),
 * because a regex denylist is easy to regress.
 *
 * Returns null when the text holds no usable `<svg>` artwork (an empty body, or
 * no `<svg>...</svg>` at all); the caller degrades that to core's placeholder.
 */
object IconSvg {

    /** Material's 24x24 grid: the default when a file declares no size. */
    private const val DEFAULT_VIEWBOX = 24.0

    private val SVG_OPEN = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val VIEW_BOX = Regex("viewBox\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    // Sanitization passes, applied to the inner markup in this order (mirrors
    // cli.js iconFromSvg):
    private val COMMENTS = Regex("<!--[\\s\\S]*?-->")
    // Elements that can execute script or inject live attributes, removed whole.
    // Beyond script/style/foreignObject this also strips SMIL animation elements
    // (animate/animateMotion/animateTransform/set): `<set attributeName="onload"
    // to="...">` and `<animate attributeName="href" to="javascript:...">` create a
    // dangerous attribute at runtime, which a regex that only scrubs *static* on*/
    // href attributes would miss -- verified empirically in JCEF/Chromium that the
    // markup survives our other passes. Static wireframe icons never animate, so
    // dropping SMIL is free here (this is also DOMPurify's default posture).
    //
    // An optional `[a-z..]:` NAMESPACE PREFIX is tolerated on both the open and
    // close tag, so `<svg:script>...</svg:script>` and `<x:set .../>` match too
    // (browsers honor the local name regardless of prefix). The prefix sits
    // OUTSIDE the captured group, so the backreference still binds the bare local
    // name and the close tag may carry its own (possibly different) prefix.
    //
    // Three tag shapes are handled so element CONTENT containing "/>" can't cause
    // an early stop and an UNCLOSED tag still swallows to end-of-input:
    //   <tag .../>                 self-closing (the /> is inside the opening tag)
    //   <tag ...>...</tag>         container with a matching close
    //   <tag ...>...<EOF>          opening tag present but unclosed
    private val DANGEROUS_ELEMENTS =
        Regex(
            "<(?:[a-z][a-z0-9]*:)?(script|style|foreignObject|animate|animateMotion|animateTransform|set)" +
                "\\b(?:[^>]*/>|[^>]*>(?:[\\s\\S]*?</(?:[a-z][a-z0-9]*:)?\\1\\s*>|[\\s\\S]*$))",
            RegexOption.IGNORE_CASE,
        )
    // on* event handlers in every attribute-value form SVG accepts. The leading
    // boundary is [\s/], not just \s: inside a start tag the HTML tokenizer treats
    // a solidus as an attribute separator, so `<path/onclick=x>` parses onclick as
    // a live attribute -- a `\s`-only boundary would let that bypass survive into
    // innerHTML. The unquoted-value class also excludes `/` so a `/`-chained
    // handler (`onerror=x/onload=y`) is matched and removed segment by segment.
    // (This closes a gap that also exists upstream in core's cli.js iconFromSvg.)
    private val EVENT_HANDLERS =
        Regex("[\\s/]on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\s/>]+)", RegexOption.IGNORE_CASE)
    // href / xlink:href that is NOT a same-document '#' reference -- kills
    // javascript:/data: URIs and external <use>/<image> fetches in one rule. Same
    // [\s/] boundary + `/`-excluding unquoted value as the handler rule, so a
    // chained `href=#a/href=javascript:x` can't smuggle the second href past the
    // "starts with #" keep-check by hiding inside the first value.
    private val HREF_ATTR =
        Regex("[\\s/](?:xlink:)?href\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s/>]+)", RegexOption.IGNORE_CASE)
    private val HASH_VALUE = Regex("^[\"']?#")
    // `style=` is removed WHOLESALE (any value), not parsed: inline CSS can carry
    // `url(javascript:...)`, IE's `expression(...)`, and `-moz-binding:url(...)`,
    // and writing a CSS-value sanitizer is its own arms race. Wireframe icons get
    // their color from presentation ATTRIBUTES (fill/stroke/currentColor), which
    // are untouched, so dropping the `style` attribute outright is a safe, lossy-
    // only-for-edge-cases trade. Same [\s/] boundary + `/`-excluding unquoted
    // value as the handler/href rules, so a `/`-separated `style=` is caught too.
    private val STYLE_ATTR =
        Regex("[\\s/]style\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s/>]+)", RegexOption.IGNORE_CASE)

    /**
     * @param text the full text of an SVG file
     * @return sanitized artwork, or null if there is no usable `<svg>` body
     */
    fun fromSvg(text: String): IconArt? {
        val open = SVG_OPEN.find(text) ?: return null
        val close = text.lastIndexOf("</svg>")
        if (close <= open.range.first) return null

        val openTag = open.value
        val viewBox = viewBoxOf(openTag)

        val inner = text.substring(open.range.last + 1, close)
        val body = inner
            .replace(COMMENTS, "")
            .replace(DANGEROUS_ELEMENTS, "")
            .replace(EVENT_HANDLERS, "")
            .replace(HREF_ATTR) { m -> if (HASH_VALUE.containsMatchIn(m.groupValues[1])) m.value else "" }
            .replace(STYLE_ATTR, "")
            .trim()

        return if (body.isEmpty()) null else IconArt(body, viewBox)
    }

    /**
     * The square grid size from the `<svg>` open tag: `viewBox`'s larger of
     * width/height, else the larger of the `width`/`height` attributes, else the
     * Material 24 default (mirrors cli.js iconFromSvg's size logic).
     */
    private fun viewBoxOf(openTag: String): Double {
        val vb = VIEW_BOX.find(openTag)
        if (vb != null) {
            val parts = vb.groupValues[1].trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size == 4) {
                val box = maxOf(parts[2], parts[3])
                if (parts.all { it.isFinite() } && box > 0) return box
            }
        }
        val h = dimAttr(openTag, "height")
        val w = dimAttr(openTag, "width")
        return when {
            h > 0 -> h
            w > 0 -> w
            else -> DEFAULT_VIEWBOX
        }
    }

    /** A numeric `name="N"` / `name="Npx"` attribute value, or 0 if absent. */
    private fun dimAttr(openTag: String, name: String): Double {
        val m = Regex("$name\\s*=\\s*\"(\\d+(?:\\.\\d+)?)(?:px)?\"", RegexOption.IGNORE_CASE).find(openTag)
        return m?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }
}
