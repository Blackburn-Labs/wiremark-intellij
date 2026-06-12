package dev.wiremark.intellij.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the JS payload escaping. No platform fixtures needed.
 */
class WiremarkPreviewPayloadTest {

    @Test
    fun `simple source round-trips as a quoted literal`() {
        assertEquals("\"Button login\"", WiremarkPreviewPayload.toJsStringLiteral("Button login"))
    }

    @Test
    fun `double quotes are escaped`() {
        assertEquals("\"a \\\"b\\\" c\"", WiremarkPreviewPayload.toJsStringLiteral("a \"b\" c"))
    }

    @Test
    fun `backslashes are escaped`() {
        // input: one backslash -> output literal contains two backslashes
        assertEquals("\"a\\\\b\"", WiremarkPreviewPayload.toJsStringLiteral("a\\b"))
    }

    @Test
    fun `newlines and tabs are escaped, not literal`() {
        val literal = WiremarkPreviewPayload.toJsStringLiteral("line1\nline2\twrapped")
        assertTrue("expected \\n escape", literal.contains("\\n"))
        assertTrue("expected \\t escape", literal.contains("\\t"))
        assertFalse("must not contain a raw newline", literal.contains("\n"))
        assertFalse("must not contain a raw tab", literal.contains("\t"))
    }

    @Test
    fun `unicode line and paragraph separators are escaped`() {
        val ls = '\u2028'.toString()
        val ps = '\u2029'.toString()
        val literal = WiremarkPreviewPayload.toJsStringLiteral("a${ls}b${ps}c")
        assertTrue("expected \\u2028", literal.contains("\\u2028"))
        assertTrue("expected \\u2029", literal.contains("\\u2029"))
        assertFalse("raw U+2028 must not survive", literal.contains(ls))
        assertFalse("raw U+2029 must not survive", literal.contains(ps))
    }

    @Test
    fun `script-closing sequence is harmless inside executeJavaScript payload`() {
        // executeJavaScript runs JS source (not HTML), but the literal must still
        // be a single valid string literal with the slash preserved.
        val literal = WiremarkPreviewPayload.toJsStringLiteral("</script>")
        assertEquals("\"</script>\"", literal)
    }

    @Test
    fun `renderCall wraps the literal in the entry-point invocation`() {
        assertEquals(
            "window.renderWiremark(\"Frame\");",
            WiremarkPreviewPayload.renderCall("Frame"),
        )
    }

    @Test
    fun `empty source produces an empty literal`() {
        assertEquals("\"\"", WiremarkPreviewPayload.toJsStringLiteral(""))
        assertEquals("window.renderWiremark(\"\");", WiremarkPreviewPayload.renderCall(""))
    }

    // --- 2-arg form: the task #6 icon bridge (added by dev6) -----------------

    @Test
    fun `renderCall with an icon map splices the JSON as a second argument`() {
        val icons = "{\"./logo.svg\":{\"body\":\"<path/>\",\"viewBox\":24}}"
        assertEquals(
            "window.renderWiremark(\"Frame\", $icons);",
            WiremarkPreviewPayload.renderCall("Frame", icons),
        )
    }

    @Test
    fun `renderCall with an empty icon object falls back to the single-arg form`() {
        // The common no-src=-icons case must emit the original clean call.
        assertEquals("window.renderWiremark(\"Frame\");", WiremarkPreviewPayload.renderCall("Frame", "{}"))
    }

    @Test
    fun `renderCall with a blank icon string falls back to the single-arg form`() {
        assertEquals("window.renderWiremark(\"Frame\");", WiremarkPreviewPayload.renderCall("Frame", ""))
    }

    @Test
    fun `renderCall still escapes the source literal when icons are present`() {
        val icons = "{\"a.svg\":{\"body\":\"<path/>\",\"viewBox\":24}}"
        val call = WiremarkPreviewPayload.renderCall("a \"b\" c", icons)
        assertTrue("source must be escaped", call.startsWith("window.renderWiremark(\"a \\\"b\\\" c\", "))
        assertTrue("icons spliced verbatim", call.endsWith(", $icons);"))
    }

    // --- 3-arg form: the IDE theme bridge ------------------------------------

    @Test
    fun `renderCall with a theme splices it as a trailing literal after the icons`() {
        val icons = "{\"a.svg\":{\"body\":\"<path/>\",\"viewBox\":24}}"
        assertEquals(
            "window.renderWiremark(\"Frame\", $icons, \"dark\");",
            WiremarkPreviewPayload.renderCall("Frame", icons, "dark"),
        )
    }

    @Test
    fun `renderCall with a theme keeps an explicit empty icon map`() {
        // Unlike the 2-arg form, blank icons must NOT collapse to the 1-arg call:
        // the theme argument is positional and would be silently dropped in the
        // common no-icons case.
        assertEquals(
            "window.renderWiremark(\"Frame\", {}, \"dark\");",
            WiremarkPreviewPayload.renderCall("Frame", "", "dark"),
        )
        assertEquals(
            "window.renderWiremark(\"Frame\", {}, \"dark\");",
            WiremarkPreviewPayload.renderCall("Frame", "{}", "dark"),
        )
    }

    @Test
    fun `renderCall normalizes any non-dark theme to a light literal`() {
        assertEquals(
            "window.renderWiremark(\"Frame\", {}, \"light\");",
            WiremarkPreviewPayload.renderCall("Frame", "{}", "light"),
        )
        // Never splice a non-constant theme: anything but exactly "dark" is light.
        assertEquals(
            "window.renderWiremark(\"Frame\", {}, \"light\");",
            WiremarkPreviewPayload.renderCall("Frame", "{}", "Dark\"); alert(1); (\""),
        )
    }

    @Test
    fun `renderCall still escapes the source literal when a theme is present`() {
        assertEquals(
            "window.renderWiremark(\"a \\\"b\\\" c\", {}, \"dark\");",
            WiremarkPreviewPayload.renderCall("a \"b\" c", "", "dark"),
        )
    }
}
