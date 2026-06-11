package dev.wiremark.intellij.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies the .wiremark file type and language are registered and wired up.
 */
class WiremarkFileTypeTest : BasePlatformTestCase() {

    fun `test wiremark extension maps to WiremarkFileType`() {
        val byExt = FileTypeManager.getInstance().getFileTypeByExtension("wiremark")
        assertEquals(WiremarkFileType, byExt)
    }

    fun `test file name resolves to WiremarkFileType`() {
        val byName = FileTypeManager.getInstance().getFileTypeByFileName("screen.wiremark")
        assertEquals(WiremarkFileType, byName)
    }

    fun `test file type exposes the wiremark language and extension`() {
        assertEquals("Wiremark", WiremarkFileType.name)
        assertEquals("wiremark", WiremarkFileType.defaultExtension)
        assertSame(WiremarkLanguage, WiremarkFileType.language)
        assertNotNull(WiremarkFileType.icon)
    }

    fun `test wiremark language is registered by id`() {
        assertSame(WiremarkLanguage, Language.findLanguageByID("Wiremark"))
    }
}
