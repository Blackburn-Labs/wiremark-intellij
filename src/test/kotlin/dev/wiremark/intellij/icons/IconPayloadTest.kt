package dev.wiremark.intellij.icons

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the icon-map JSON encoding. No platform fixtures.
 */
class IconPayloadTest {

    @Test
    fun `empty map encodes to the empty object literal`() {
        assertEquals("{}", IconPayload.toJson(emptyMap()))
    }

    @Test
    fun `a single entry encodes body and viewBox keyed by raw src`() {
        val json = IconPayload.toJson(mapOf("./logo.svg" to IconArt("<path d=\"M0 0\"/>", 24.0)))
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("./logo.svg"))
        val entry = obj.getAsJsonObject("./logo.svg")
        assertEquals("<path d=\"M0 0\"/>", entry.get("body").asString)
        assertEquals(24.0, entry.get("viewBox").asDouble, 0.0)
    }

    @Test
    fun `multiple entries all appear`() {
        val json = IconPayload.toJson(
            linkedMapOf(
                "a.svg" to IconArt("<path d=\"A\"/>", 16.0),
                "b/c.svg" to IconArt("<path d=\"B\"/>", 48.0),
            ),
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(16.0, obj.getAsJsonObject("a.svg").get("viewBox").asDouble, 0.0)
        assertEquals(48.0, obj.getAsJsonObject("b/c.svg").get("viewBox").asDouble, 0.0)
    }

    @Test
    fun `body containing quotes and angle brackets round-trips through JSON`() {
        // The body is real SVG markup with double quotes; gson must escape it so
        // the spliced JS object literal stays valid.
        val body = "<path fill=\"currentColor\" d=\"M1 1L2 2\"/><circle r=\"3\"/>"
        val json = IconPayload.toJson(mapOf("x.svg" to IconArt(body, 24.0)))
        // Valid JSON (parses) and the body survives intact.
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(body, obj.getAsJsonObject("x.svg").get("body").asString)
        // The raw string must not contain an unescaped </script> that could break
        // out if this landed in an HTML <script> (defense; it ships via executeJS).
        assertFalse(json.contains("</script>"))
    }

    @Test
    fun `a src key with a space is preserved as a JSON key`() {
        val json = IconPayload.toJson(mapOf("my icons/logo.svg" to IconArt("<path d=\"M0 0\"/>", 24.0)))
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("my icons/logo.svg"))
    }
}
