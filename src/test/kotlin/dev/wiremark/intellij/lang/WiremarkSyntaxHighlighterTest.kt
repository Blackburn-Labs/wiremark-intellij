package dev.wiremark.intellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies the [WiremarkSyntaxHighlighter] returns a color key for every
 * wiremark token type and an empty array for structural tokens, and that the
 * factory is wired to the [WiremarkLanguage] so the editor actually uses it.
 */
class WiremarkSyntaxHighlighterTest : BasePlatformTestCase() {

    private val highlighter = WiremarkSyntaxHighlighter()

    fun `test every colored token type maps to exactly one attribute key`() {
        val expected = mapOf(
            WiremarkTokenTypes.COMMENT to WiremarkSyntaxHighlighter.COMMENT,
            WiremarkTokenTypes.COMPONENT to WiremarkSyntaxHighlighter.COMPONENT,
            WiremarkTokenTypes.ATTRIBUTE to WiremarkSyntaxHighlighter.ATTRIBUTE,
            WiremarkTokenTypes.EQUALS to WiremarkSyntaxHighlighter.EQUALS,
            WiremarkTokenTypes.STRING to WiremarkSyntaxHighlighter.STRING,
            WiremarkTokenTypes.ANCHOR to WiremarkSyntaxHighlighter.ANCHOR,
            WiremarkTokenTypes.NUMBER to WiremarkSyntaxHighlighter.NUMBER,
            WiremarkTokenTypes.IDENTIFIER to WiremarkSyntaxHighlighter.IDENTIFIER,
        )
        for ((token, key) in expected) {
            val keys = highlighter.getTokenHighlights(token)
            assertEquals("one key for $token", 1, keys.size)
            assertEquals("correct key for $token", key, keys[0])
        }
    }

    fun `test structural tokens carry no color`() {
        assertEquals(0, highlighter.getTokenHighlights(WiremarkTokenTypes.WHITE_SPACE).size)
        assertEquals(0, highlighter.getTokenHighlights(WiremarkTokenTypes.NEWLINE).size)
        assertEquals(0, highlighter.getTokenHighlights(null).size)
    }

    fun `test the highlighting lexer is the wiremark lexer`() {
        assertTrue(highlighter.highlightingLexer is WiremarkLexer)
    }

    fun `test factory provides a wiremark highlighter for the language`() {
        val factory = WiremarkSyntaxHighlighterFactory()
        val h = factory.getSyntaxHighlighter(project, null)
        assertTrue(h is WiremarkSyntaxHighlighter)
    }

    fun `test color keys fall back to default language colors so all themes work`() {
        // Each wiremark key must carry a non-null fallback so an un-customized
        // theme still colors it. (createTextAttributesKey records the fallback.)
        val keys = listOf(
            WiremarkSyntaxHighlighter.COMMENT,
            WiremarkSyntaxHighlighter.COMPONENT,
            WiremarkSyntaxHighlighter.ATTRIBUTE,
            WiremarkSyntaxHighlighter.EQUALS,
            WiremarkSyntaxHighlighter.STRING,
            WiremarkSyntaxHighlighter.ANCHOR,
            WiremarkSyntaxHighlighter.NUMBER,
            WiremarkSyntaxHighlighter.IDENTIFIER,
        )
        for (key in keys) {
            assertNotNull("${key.externalName} must have a fallback", key.fallbackAttributeKey)
        }
    }
}
