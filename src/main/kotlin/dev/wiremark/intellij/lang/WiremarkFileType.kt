package dev.wiremark.intellij.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * File type for standalone `*.wiremark` files.
 *
 * v1 treats the body as plain text (no lexer); a real [com.intellij.lexer.Lexer]
 * and SyntaxHighlighter are added in a later milestone against [WiremarkLanguage].
 */
object WiremarkFileType : LanguageFileType(WiremarkLanguage) {
    const val DEFAULT_EXTENSION: String = "wiremark"

    override fun getName(): String = "Wiremark"

    override fun getDescription(): String = "Wiremark wireframe diagram"

    override fun getDefaultExtension(): String = DEFAULT_EXTENSION

    override fun getIcon(): Icon = WiremarkIcons.FILE
}
