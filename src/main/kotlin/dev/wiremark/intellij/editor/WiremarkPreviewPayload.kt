package dev.wiremark.intellij.editor

import com.google.gson.JsonPrimitive

/**
 * Pure, browser-free logic for turning wiremark source into the JavaScript
 * snippet pushed into the JCEF preview. Extracted from the editor so it can be
 * unit-tested without a live Chromium (JCEF cannot run headless).
 */
object WiremarkPreviewPayload {
    // U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR: valid in JSON but
    // illegal inside a JavaScript string literal. Built from code points so this
    // source file stays pure ASCII.
    private val LINE_SEPARATOR = '\u2028'.toString()
    private val PARAGRAPH_SEPARATOR = '\u2029'.toString()

    /**
     * Encode an arbitrary string as a JavaScript string literal (including the
     * surrounding quotes), safe to splice into executable JS source.
     *
     * Gson produces a valid JSON string literal -- correct for quotes,
     * backslashes, and C0 control characters. We additionally escape U+2028 /
     * U+2029 so the result is a valid JS literal as well, not merely valid JSON.
     */
    fun toJsStringLiteral(source: String): String {
        val json = JsonPrimitive(source).toString()
        return json
            .replace(LINE_SEPARATOR, "\\u2028")
            .replace(PARAGRAPH_SEPARATOR, "\\u2029")
    }

    /**
     * The full JS statement to evaluate in the preview document for [source],
     * with no injected `src=` icons. Calls the shell's single-argument
     * `window.renderWiremark(src)` form (the preview treats a missing icon map as
     * "no src= icons", degrading any to core's placeholder). Kept byte-identical
     * to the original contract so callers/tests that don't deal in icons are
     * unchanged.
     */
    fun renderCall(source: String): String =
        "window.renderWiremark(" + toJsStringLiteral(source) + ");"

    /**
     * The full JS statement to evaluate in the preview document for [source],
     * shipping [iconsJson] as the second argument to the shell's
     * `window.renderWiremark(src, icons)` entry point (task #6 icon bridge).
     *
     * [iconsJson] is an already-encoded JSON object literal (raw-`src` ->
     * `{ body, viewBox }`, from [dev.wiremark.intellij.icons.IconPayload]),
     * spliced in verbatim: it is machine-generated JSON, never user text that
     * needs string-literal escaping. The preview turns it into a synchronous
     * `loadIcon` map lookup; a `src` missing from it degrades to core's
     * placeholder. A blank value falls back to the single-argument form so the
     * emitted JS call is always well-formed.
     */
    fun renderCall(source: String, iconsJson: String): String {
        val icons = iconsJson.ifBlank { "{}" }
        if (icons == "{}") return renderCall(source)
        return "window.renderWiremark(" + toJsStringLiteral(source) + ", " + icons + ");"
    }

    /**
     * The full JS statement for [source] carrying both the [iconsJson] map and
     * the IDE [theme] as the third argument:
     * `window.renderWiremark(src, icons, "dark")`.
     *
     * Unlike the 2-arg form, blank icons must NOT collapse to the
     * single-argument call -- that would silently drop the theme in the common
     * no-icons case -- so an explicit `{}` map is spliced to keep the argument
     * positions stable.
     *
     * [theme] is normalized here to a constant `"dark"` / `"light"` literal (any
     * other value renders light, matching core's own unknown-theme fallback), so
     * the spliced JS never carries non-constant text. The platform-aware theme
     * read lives in the editor; this object stays browser- and platform-free.
     */
    fun renderCall(source: String, iconsJson: String, theme: String): String {
        val icons = iconsJson.ifBlank { "{}" }
        val themeLiteral = if (theme == "dark") "\"dark\"" else "\"light\""
        return "window.renderWiremark(" + toJsStringLiteral(source) + ", " + icons + ", " + themeLiteral + ");"
    }
}
