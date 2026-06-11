package dev.wiremark.intellij.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the Icons-block `src=` scanner. No platform fixtures: the
 * scanner is plain string logic over wiremark source text.
 */
class IconSrcScannerTest {

    @Test
    fun `no Icons block yields no refs`() {
        val src =
            """
            Wireframe
              Button "Login"
              Icon Search
            """.trimIndent()
        assertEquals(emptyList<IconSrcRef>(), IconSrcScanner.scan(src))
    }

    @Test
    fun `a single bare src entry is found`() {
        val src =
            """
            Icons
              Logo src=./logo.svg
            Wireframe
              Image
            """.trimIndent()
        assertEquals(listOf(IconSrcRef("Logo", "./logo.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `a quoted src with spaces stays one token`() {
        val src =
            """
            Icons
              Logo src="my icons/logo.svg"
            """.trimIndent()
        assertEquals(listOf(IconSrcRef("Logo", "my icons/logo.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `multiple src entries are returned in document order`() {
        val src =
            """
            Icons
              A src=a.svg
              B src=sub/b.svg
              C "M0 0h24v24H0z"
              D src=../shared/d.svg
            Wireframe
            """.trimIndent()
        assertEquals(
            listOf(
                IconSrcRef("A", "a.svg"),
                IconSrcRef("B", "sub/b.svg"),
                IconSrcRef("D", "../shared/d.svg"),
            ),
            IconSrcScanner.scan(src),
        )
    }

    @Test
    fun `inline path-data entries (no src=) are ignored`() {
        val src =
            """
            Icons
              Heart "M12 21l-1.45-1.32"
              Star viewBox=48 "M0 0h48"
            """.trimIndent()
        assertEquals(emptyList<IconSrcRef>(), IconSrcScanner.scan(src))
    }

    @Test
    fun `the block ends at the next top-level node`() {
        // A src= on a sibling top-level Wireframe child must NOT be collected: it
        // is outside the Icons block. (Wireframe children are more indented, but
        // the block already closed at the indent-0 Wireframe line.)
        val src =
            """
            Icons
              Logo src=logo.svg
            Wireframe
              Thing src=not-an-icon.svg
            """.trimIndent()
        assertEquals(listOf(IconSrcRef("Logo", "logo.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `trailing comment after src is stripped`() {
        val src =
            """
            Icons
              Logo src=logo.svg   // company mark
            """.trimIndent()
        assertEquals(listOf(IconSrcRef("Logo", "logo.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `a slash-slash inside a quoted src is not a comment`() {
        val src =
            """
            Icons
              Logo src="a//b.svg"
            """.trimIndent()
        assertEquals(listOf(IconSrcRef("Logo", "a//b.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `viewBox and src on the same entry still yields the src`() {
        val src =
            """
            Icons
              Logo src=logo.svg viewBox=32
            """.trimIndent()
        assertEquals(listOf(IconSrcRef("Logo", "logo.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `a tab in indentation disables scanning (lexer would reject the doc)`() {
        // The Icons entry line is indented with a tab; the real lexer throws on
        // that, so we bail rather than resolve files from a doc core will reject.
        val src = "Icons\n\tLogo src=logo.svg\n"
        assertEquals(emptyList<IconSrcRef>(), IconSrcScanner.scan(src))
    }

    @Test
    fun `blank and comment-only lines inside the block are skipped`() {
        val src =
            """
            Icons

              // a leading note
              Logo src=logo.svg

              Mark src=mark.svg
            """.trimIndent()
        assertEquals(
            listOf(IconSrcRef("Logo", "logo.svg"), IconSrcRef("Mark", "mark.svg")),
            IconSrcScanner.scan(src),
        )
    }

    @Test
    fun `CRLF line endings are handled`() {
        val src = "Icons\r\n  Logo src=logo.svg\r\nWireframe\r\n"
        assertEquals(listOf(IconSrcRef("Logo", "logo.svg")), IconSrcScanner.scan(src))
    }

    @Test
    fun `an Icons substring component name does not open the block`() {
        // "IconsPanel" must not be mistaken for the "Icons" block opener.
        val src =
            """
            Wireframe
              IconsPanel src=nope.svg
            """.trimIndent()
        assertEquals(emptyList<IconSrcRef>(), IconSrcScanner.scan(src))
    }

    @Test
    fun `indented Icons is not a top-level block`() {
        // The Icons block opener must be at indent 0; an indented "Icons" is a
        // child element, not the document-level block.
        val src =
            """
            Wireframe
              Icons
                Logo src=logo.svg
            """.trimIndent()
        assertEquals(emptyList<IconSrcRef>(), IconSrcScanner.scan(src))
    }

    @Test
    fun `empty source yields no refs`() {
        assertEquals(emptyList<IconSrcRef>(), IconSrcScanner.scan(""))
    }

    @Test
    fun `unterminated quote in src is tolerated, not thrown`() {
        // The lexer would throw "unterminated string literal"; the scanner must
        // never throw -- it closes the chunk and returns whatever it parsed.
        val src =
            """
            Icons
              Logo src="logo.svg
            """.trimIndent()
        // We don't assert the exact value (best-effort); only that it didn't throw
        // and produced at most one ref for the entry.
        val refs = IconSrcScanner.scan(src)
        assertTrue(refs.size <= 1)
    }
}
