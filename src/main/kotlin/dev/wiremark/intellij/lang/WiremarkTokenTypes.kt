package dev.wiremark.intellij.lang

import com.intellij.psi.tree.IElementType

/**
 * Token types produced by [WiremarkLexer] and consumed by the
 * [WiremarkSyntaxHighlighter].
 *
 * The set is derived from the real `@wiremark/core` lexer
 * (`src/lexer.js`): source is line-oriented; each significant line is a
 * component name followed by a flat list of tokens, each token being a
 * double-quoted literal, a bare run, or a `key=value` pair. A `//` run that
 * is not inside a string opens an end-of-line comment; `#` is the anchor
 * sigil, never a comment.
 *
 * This is a *highlighting* lexer, so it is deliberately more permissive than
 * core's parser: it never rejects input. It distinguishes the lexical shapes
 * core distinguishes (comment / component / key / value / string / number /
 * anchor / punctuation) but assigns no semantics beyond what a single line of
 * text reveals.
 */
object WiremarkTokenTypes {
    /** `//` end-of-line comment (only outside a string). */
    @JvmField val COMMENT: IElementType = WiremarkElementType("WIREMARK_COMMENT")

    /** First chunk of a line: the component / element name (e.g. `Button`). */
    @JvmField val COMPONENT: IElementType = WiremarkElementType("WIREMARK_COMPONENT")

    /** The `key` of a `key=value` token (e.g. the `gap` in `gap=2`). */
    @JvmField val ATTRIBUTE: IElementType = WiremarkElementType("WIREMARK_ATTRIBUTE")

    /** The `=` separating a key from its value. */
    @JvmField val EQUALS: IElementType = WiremarkElementType("WIREMARK_EQUALS")

    /** A double-quoted text literal, including its surrounding quotes. */
    @JvmField val STRING: IElementType = WiremarkElementType("WIREMARK_STRING")

    /** An `#id` anchor / reference token (the spec's anchor sigil). */
    @JvmField val ANCHOR: IElementType = WiremarkElementType("WIREMARK_ANCHOR")

    /** A numeric bare token or numeric value (e.g. `2`, `120`, `1.5`). */
    @JvmField val NUMBER: IElementType = WiremarkElementType("WIREMARK_NUMBER")

    /**
     * A bare identifier: an enum value, a boolean flag, or a value of a
     * `key=value` pair that is not a string/number/anchor (e.g. `col`,
     * `contained`, `primary`).
     */
    @JvmField val IDENTIFIER: IElementType = WiremarkElementType("WIREMARK_IDENTIFIER")

    /** Run of spaces/tabs. Whitespace is significant for indentation but carries no color. */
    @JvmField val WHITE_SPACE: IElementType = WiremarkElementType("WIREMARK_WHITE_SPACE")

    /** Line terminator(s): `\n`, `\r`, or `\r\n`. */
    @JvmField val NEWLINE: IElementType = WiremarkElementType("WIREMARK_NEWLINE")
}
