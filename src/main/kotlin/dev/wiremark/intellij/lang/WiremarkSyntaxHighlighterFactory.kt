package dev.wiremark.intellij.lang

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Supplies the [WiremarkSyntaxHighlighter] for wiremark content -- both
 * `*.wiremark` files and ` ```wireframe ` fences injected into Markdown.
 * Registered via the `com.intellij.lang.syntaxHighlighterFactory` EP keyed on
 * [WiremarkLanguage].
 */
class WiremarkSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        WiremarkSyntaxHighlighter()
}
