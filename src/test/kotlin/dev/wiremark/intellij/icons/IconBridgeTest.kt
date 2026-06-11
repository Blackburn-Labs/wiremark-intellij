package dev.wiremark.intellij.icons

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform tests for the icon bridge's VFS resolution and security gates. Uses
 * the light project fixture, whose content root is the in-memory temp source
 * directory: files added via [myFixture] live inside it, so they pass the
 * content-root check; a file created above that root is "outside the project"
 * and must be rejected.
 *
 * Covers the deliverable's required security cases: resolution relative to the
 * document, path-traversal rejection, the size cap, and end-to-end sanitization.
 */
class IconBridgeTest : BasePlatformTestCase() {

    private val sampleSvg = """<svg viewBox="0 0 24 24"><path d="M0 0h24v24H0z"/></svg>"""

    /** A *.wiremark document whose Icons block points at [srcValues]. */
    private fun documentSource(vararg srcValues: String): String =
        buildString {
            append("Icons\n")
            srcValues.forEachIndexed { i, s -> append("  Icon$i src=$s\n") }
            append("Wireframe\n  Image\n")
        }

    fun `test resolves a sibling icon inside the content root`() {
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("./logo.svg")).virtualFile
        myFixture.addFileToProject("ui/logo.svg", sampleSvg)

        val map = IconBridge.buildIconMap(project, doc, doc.let { readDoc(it) })
        assertEquals(setOf("./logo.svg"), map.keys)
        val art = map["./logo.svg"]!!
        assertEquals("""<path d="M0 0h24v24H0z"/>""", art.body)
        assertEquals(24.0, art.viewBox, 0.0)
    }

    fun `test resolves an icon in a subdirectory relative to the document`() {
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("assets/mark.svg")).virtualFile
        myFixture.addFileToProject("ui/assets/mark.svg", """<svg width="16" height="16"><path d="M1 1"/></svg>""")

        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        assertEquals(setOf("assets/mark.svg"), map.keys)
        assertEquals(16.0, map["assets/mark.svg"]!!.viewBox, 0.0)
    }

    fun `test a missing icon file is omitted (degrades to placeholder)`() {
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("./nope.svg")).virtualFile
        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        assertTrue("missing file must not appear in the map", map.isEmpty())
    }

    fun `test path traversal escaping the content root is rejected`() {
        // The document lives under the content root; create a real file ABOVE the
        // root and reach it with ../ segments. It resolves to a genuine file that
        // lands outside any content root, so the bridge must reject it (this is
        // the isInContent gate, not the unresolvable-path branch).
        val doc = myFixture.addFileToProject("ui/screen.wiremark", "Wireframe\n").virtualFile
        val outside = createFileAboveContentRoot("secret.svg")
        val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
        // Sanity: the file we want to reject is genuinely outside content.
        assertFalse(
            "test setup: target should be outside the content root",
            fileIndex.isInContent(outside),
        )

        // Build the exact ../ path from the document's directory to `outside`, so
        // the traversal resolves to that real file regardless of fixture depth.
        val rel = relativePath(doc.parent!!, outside)
        // Sanity: that path really does resolve to the outside file from the doc
        // dir via the SAME primitive the bridge uses (findFileByRelativePath).
        assertEquals(outside, doc.parent!!.findFileByRelativePath(rel))

        val source = "Icons\n  Bad src=\"$rel\"\nWireframe\n"
        val map = IconBridge.buildIconMap(project, doc, source)
        assertTrue("a traversal target outside content must be rejected", map.isEmpty())
    }

    fun `test an absolute src is treated base-relative, never resolved from the FS root`() {
        // A leading-/ src must NOT reach an absolute filesystem path (the gotcha in
        // VfsUtilCore.findRelativeFile); findFileByRelativePath ignores the leading
        // slash and walks from the document dir, so it cannot resolve `/etc/...`.
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("/etc/hosts")).virtualFile
        // Create a real file at the base-relative interpretation "etc/hosts" to
        // prove the leading slash was dropped and resolution stayed under the doc.
        myFixture.addFileToProject("ui/etc/hosts", "<svg><path d=\"M2 2\"/></svg>")

        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        // The src key is the raw "/etc/hosts"; it resolved to the in-content
        // ui/etc/hosts (base-relative), NOT the real /etc/hosts on the host.
        assertEquals(setOf("/etc/hosts"), map.keys)
        assertTrue(map["/etc/hosts"]!!.body.contains("""d="M2 2""""))
    }

    fun `test an oversize icon file is skipped`() {
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("big.svg")).virtualFile
        // Build an SVG just over the 1 MB cap and write it straight into the
        // document's directory via VFS (no PSI overhead for a 1 MB file).
        val filler = "z".repeat((IconBridge.MAX_ICON_BYTES + 16).toInt())
        val big = runWriteAction {
            val f = doc.parent!!.createChildData(this, "big.svg")
            com.intellij.openapi.vfs.VfsUtil.saveText(f, """<svg><path d="M0 0"/><!--$filler--></svg>""")
            f
        }
        // Sanity: the file really is over the cap (so we're testing the size gate,
        // not some other rejection).
        assertTrue("test setup: file should exceed the cap", big.length > IconBridge.MAX_ICON_BYTES)

        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        assertTrue("file over the size cap must be skipped", map.isEmpty())
    }

    fun `test a script in the icon file is sanitized out of the shipped body`() {
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("evil.svg")).virtualFile
        myFixture.addFileToProject(
            "ui/evil.svg",
            """<svg><script>alert(1)</script><path d="M0 0" onclick="x()"/></svg>""",
        )

        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        val body = map["evil.svg"]!!.body
        assertFalse(body.contains("<script", ignoreCase = true))
        assertFalse(body.contains("onclick", ignoreCase = true))
        assertTrue(body.contains("""d="M0 0""""))
    }

    fun `test no Icons block ships an empty map`() {
        val doc = myFixture.addFileToProject("ui/plain.wiremark", "Wireframe\n  Button \"Hi\"\n").virtualFile
        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        assertTrue(map.isEmpty())
        assertEquals("{}", IconBridge.buildIconJson(project, doc, readDoc(doc)))
    }

    fun `test duplicate src values are read once`() {
        val doc = myFixture.addFileToProject(
            "ui/screen.wiremark",
            "Icons\n  A src=logo.svg\n  B src=logo.svg\nWireframe\n",
        ).virtualFile
        myFixture.addFileToProject("ui/logo.svg", sampleSvg)

        val map = IconBridge.buildIconMap(project, doc, readDoc(doc))
        assertEquals(setOf("logo.svg"), map.keys)
    }

    fun `test buildIconJson encodes a resolved icon`() {
        val doc = myFixture.addFileToProject("ui/screen.wiremark", documentSource("./logo.svg")).virtualFile
        myFixture.addFileToProject("ui/logo.svg", sampleSvg)

        val json = IconBridge.buildIconJson(project, doc, readDoc(doc))
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("./logo.svg"))
        assertEquals(24.0, obj.getAsJsonObject("./logo.svg").get("viewBox").asDouble, 0.0)
    }

    /** Read a VFS file's text (the editor passes the live document text). */
    private fun readDoc(vf: VirtualFile): String =
        com.intellij.openapi.vfs.VfsUtilCore.loadText(vf)

    /**
     * A `../`-prefixed relative path from directory [from] to file [to], walking
     * up to their common ancestor. Used to build a traversal `src=` that resolves
     * to a real file outside the content root at the fixture's actual depth.
     */
    private fun relativePath(from: VirtualFile, to: VirtualFile): String {
        val fromChain = ancestry(from) // root..from
        val toChain = ancestry(to) // root..to
        var common = 0
        while (common < fromChain.size && common < toChain.size && fromChain[common] == toChain[common]) common++
        val ups = fromChain.size - common
        val down = toChain.drop(common).map { it.name }
        return (List(ups) { ".." } + down).joinToString("/")
    }

    /** The chain of files from the filesystem root down to [file], inclusive. */
    private fun ancestry(file: VirtualFile): List<VirtualFile> {
        val chain = ArrayList<VirtualFile>()
        var cur: VirtualFile? = file
        while (cur != null) {
            chain.add(cur)
            cur = cur.parent
        }
        return chain.reversed()
    }

    /**
     * Create an EMPTY file in the temp filesystem one level ABOVE the content root,
     * so it is reachable by `../` from a document inside the root but is itself
     * outside any content root. The fixture's content root is the temp source dir;
     * its parent is outside content.
     *
     * The file is intentionally left empty: the bridge rejects a traversal target at
     * the `isInContent` gate BEFORE it reads the file, so the content is irrelevant to
     * what this test asserts. Writing content here (VfsUtil.saveText) would instead
     * trip the platform's content-based file-type detection on the in-memory temp FS
     * -- a flaky "Does not exist: /secret.svg" race during the write -- so we don't.
     */
    private fun createFileAboveContentRoot(name: String): VirtualFile {
        val contentRoot = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.first()
        val above = contentRoot.parent ?: error("content root has no parent in the test fixture")
        return runWriteAction {
            above.findChild(name) ?: above.createChildData(this, name)
        }
    }
}
