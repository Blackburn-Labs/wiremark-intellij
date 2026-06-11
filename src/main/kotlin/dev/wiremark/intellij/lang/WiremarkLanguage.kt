package dev.wiremark.intellij.lang

import com.intellij.lang.Language

/**
 * The wiremark DSL language.
 *
 * v1 keeps this a minimal singleton: it exists so a [WiremarkFileType] can be a
 * proper [com.intellij.openapi.fileTypes.LanguageFileType] and so the Markdown
 * plugin can inject ` ```wireframe ` fences into this language later. Syntax
 * highlighting (lexer + SyntaxHighlighter) is layered on top of this same
 * singleton in a later milestone -- keep it free of editor concerns.
 */
object WiremarkLanguage : Language("Wiremark") {
    private fun readResolve(): Any = WiremarkLanguage

    override fun getDisplayName(): String = "Wiremark"

    override fun isCaseSensitive(): Boolean = true
}
