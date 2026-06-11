package dev.wiremark.intellij.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.wiremark.intellij.lang.WiremarkLanguage
import org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider

/**
 * Verifies the fence info-string -> language mapping and that the provider is
 * actually registered on the markdown `fenceLanguageProvider` EP, so a
 * ` ```wireframe ` / ` ```wiremark ` fence body is injected as the wiremark
 * language (and thus syntax-highlighted) in the Markdown editor.
 */
class WiremarkFenceLanguageProviderTest : BasePlatformTestCase() {

    private val provider = WiremarkFenceLanguageProvider()

    fun `test canonical and alias info strings map to the wiremark language`() {
        assertSame(WiremarkLanguage, provider.getLanguageByInfoString("wireframe"))
        assertSame(WiremarkLanguage, provider.getLanguageByInfoString("wiremark"))
    }

    fun `test info string matching is case-insensitive and trimmed`() {
        assertSame(WiremarkLanguage, provider.getLanguageByInfoString("Wireframe"))
        assertSame(WiremarkLanguage, provider.getLanguageByInfoString("WIREMARK"))
        assertSame(WiremarkLanguage, provider.getLanguageByInfoString("  wireframe  "))
    }

    fun `test unrelated info strings are not claimed`() {
        assertNull(provider.getLanguageByInfoString("mermaid"))
        assertNull(provider.getLanguageByInfoString("kotlin"))
        assertNull(provider.getLanguageByInfoString(""))
        assertNull(provider.getLanguageByInfoString("wireframes")) // not a substring match
    }

    fun `test provider is registered on the markdown fence-language EP`() {
        val registered = CodeFenceLanguageProvider.EP_NAME.extensionList
            .any { it is WiremarkFenceLanguageProvider }
        assertTrue("WiremarkFenceLanguageProvider must be registered", registered)
    }

    fun `test a registered provider resolves wiremark fences end to end`() {
        // Whichever provider the platform consults, asking the EP for the
        // language of a wireframe fence must yield the wiremark language.
        val resolved = CodeFenceLanguageProvider.EP_NAME.extensionList
            .firstNotNullOfOrNull { it.getLanguageByInfoString("wireframe") }
        assertSame(WiremarkLanguage, resolved)
    }
}
