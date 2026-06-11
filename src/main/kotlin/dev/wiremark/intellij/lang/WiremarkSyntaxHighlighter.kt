package dev.wiremark.intellij.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * Maps [WiremarkLexer] tokens to colors. Every attribute key is created
 * `createTextAttributesKey(name, fallback)` where the fallback is a
 * [DefaultLanguageHighlighterColors] key, so wiremark inherits sensible colors
 * from whatever theme is active (light, dark, high-contrast, or a custom one)
 * with no per-theme work. The wiremark-specific names also let a user (or a
 * future ColorSettingsPage) override any single token independently.
 */
class WiremarkSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = WiremarkLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        KEYS[tokenType] ?: EMPTY

    companion object {
        val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT,
        )

        /** Component / element name -- the keyword-like head of every line. */
        val COMPONENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_COMPONENT", DefaultLanguageHighlighterColors.KEYWORD,
        )

        /** The `key` of a `key=value` attribute. */
        val ATTRIBUTE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_ATTRIBUTE", DefaultLanguageHighlighterColors.INSTANCE_FIELD,
        )

        val EQUALS: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_EQUALS", DefaultLanguageHighlighterColors.OPERATION_SIGN,
        )

        val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_STRING", DefaultLanguageHighlighterColors.STRING,
        )

        /** `#id` anchor / reference. Colored like a metadata/label token. */
        val ANCHOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_ANCHOR", DefaultLanguageHighlighterColors.METADATA,
        )

        val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_NUMBER", DefaultLanguageHighlighterColors.NUMBER,
        )

        /** Bare enum value / boolean flag / unkeyed value. */
        val IDENTIFIER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "WIREMARK_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER,
        )

        private val EMPTY = emptyArray<TextAttributesKey>()

        private val KEYS: Map<IElementType, Array<TextAttributesKey>> = mapOf(
            WiremarkTokenTypes.COMMENT to arrayOf(COMMENT),
            WiremarkTokenTypes.COMPONENT to arrayOf(COMPONENT),
            WiremarkTokenTypes.ATTRIBUTE to arrayOf(ATTRIBUTE),
            WiremarkTokenTypes.EQUALS to arrayOf(EQUALS),
            WiremarkTokenTypes.STRING to arrayOf(STRING),
            WiremarkTokenTypes.ANCHOR to arrayOf(ANCHOR),
            WiremarkTokenTypes.NUMBER to arrayOf(NUMBER),
            WiremarkTokenTypes.IDENTIFIER to arrayOf(IDENTIFIER),
        )
    }
}
