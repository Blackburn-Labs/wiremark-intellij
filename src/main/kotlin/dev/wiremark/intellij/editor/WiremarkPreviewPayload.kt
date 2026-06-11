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
     * The full JS statement to evaluate in the preview document for [source].
     * Calls the shell's `window.renderWiremark(src)` entry point.
     */
    fun renderCall(source: String): String =
        "window.renderWiremark(" + toJsStringLiteral(source) + ");"
}
