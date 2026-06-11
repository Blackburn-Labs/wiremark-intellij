package dev.wiremark.intellij.markdown

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import dev.wiremark.intellij.lang.WiremarkFileType
import dev.wiremark.intellij.lang.WiremarkLanguage
import org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider

/**
 * Maps the ` ```wireframe ` / ` ```wiremark ` Markdown fence info strings to
 * [WiremarkLanguage], so those fences get the wiremark in-editor syntax
 * highlighting via language injection (this is the editor side; rendering the
 * fence in the *preview* is dev3's separate browser-preview extension).
 *
 * Registered through the `org.intellij.markdown.fenceLanguageProvider` EP
 * (interface `org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider`),
 * verified against the bundled markdown plugin for 2025.2. This is the same
 * mechanism the platform's own Mermaid and PlantUML fence providers use.
 *
 * Both `wireframe` (canonical) and `wiremark` are accepted, matched
 * case-insensitively to be forgiving of how authors type a fence tag.
 */
class WiremarkFenceLanguageProvider : CodeFenceLanguageProvider {

    override fun getLanguageByInfoString(infoString: String): Language? =
        if (isWiremarkInfoString(infoString)) WiremarkLanguage else null

    override fun getCompletionVariantsForInfoString(parameters: CompletionParameters): List<LookupElement> {
        val icon = WiremarkFileType.icon
        return INFO_STRINGS.map { name ->
            LookupElementBuilder.create(name).withIcon(icon)
        }
    }

    private fun isWiremarkInfoString(infoString: String): Boolean =
        INFO_STRINGS.any { it.equals(infoString.trim(), ignoreCase = true) }

    companion object {
        /** Accepted fence info strings (canonical first). Both render identically. */
        private val INFO_STRINGS = listOf("wireframe", "wiremark")
    }
}
