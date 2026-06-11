package dev.wiremark.intellij.icons

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Bridges `Icons`-block `src=` entries from a *.wiremark document to the JCEF
 * preview, where core's `loadIcon` callback must be synchronous and JS cannot
 * read the workspace (tasks/PLAN.md "Icons with src=", the v2 plan).
 *
 * For each `src=` entry the scanner finds, the Kotlin side resolves the file
 * relative to the DOCUMENT, reads it (size-capped) through the VFS, sanitizes the
 * SVG ([IconSvg]), and assembles a `rawSrc -> { body, viewBox }` map that ships
 * as a JSON object the preview's `loadIcon` looks up synchronously. Anything that
 * fails resolution, the security checks, or the size cap is simply omitted: core
 * then degrades that icon to its `iconGlyph` placeholder + a soft diagnostic,
 * which it already does gracefully.
 *
 * Security (the reviewers will probe these; all non-negotiable):
 *  - Paths resolve relative to the document's own file ONLY -- never the project
 *    root or CWD -- mirroring how the CLI loads `src=` relative to its input.
 *  - A resolved file MUST live inside the project's content roots
 *    ([ProjectFileIndex.isInContent]); this rejects `../../../etc/passwd`-style
 *    traversal that escapes the project, even though the document itself is in it.
 *  - Reads are size-capped (~1 MB/icon): an oversize file is skipped without
 *    being read into memory.
 *  - All file access goes through the VFS ([VfsUtilCore]/[VirtualFile]), never
 *    raw java.io, so it honors the platform's file model and read locks.
 */
object IconBridge {
    private val LOG = logger<IconBridge>()

    /** Per-icon read cap. SVG icon files are KBs; 1 MB is generous headroom. */
    const val MAX_ICON_BYTES: Long = 1L * 1024 * 1024

    /**
     * Build the icon map for [source] (the document's text) whose file is
     * [documentFile], within [project]. Returns raw-`src` -> sanitized artwork
     * for every entry that resolved, passed the content-root + size checks, and
     * yielded usable `<svg>` artwork. Never throws: a failure anywhere degrades
     * to an omitted entry (core renders the placeholder).
     *
     * Wrapped in a read action because it touches the VFS / project model; called
     * from the editor's debounce on the UI thread.
     */
    fun buildIconMap(project: Project, documentFile: VirtualFile, source: String): Map<String, IconArt> {
        val refs = IconSrcScanner.scan(source)
        if (refs.isEmpty()) return emptyMap()

        val parent = documentFile.parent ?: return emptyMap()
        return try {
            ReadAction.compute<Map<String, IconArt>, RuntimeException> {
                if (project.isDisposed) return@compute emptyMap()
                val fileIndex = ProjectFileIndex.getInstance(project)
                val out = LinkedHashMap<String, IconArt>()
                // De-dupe by raw src so two entries pointing at the same path are
                // read once; the first declaration wins (core's convention).
                for (src in refs.map { it.src }.distinct()) {
                    val art = resolveOne(fileIndex, parent, src)
                    if (art != null) out[src] = art
                }
                out
            }
        } catch (t: Throwable) {
            // Defensive: the whole bridge is best-effort. A model/VFS hiccup must
            // never break rendering -- degrade the entire map to empty.
            LOG.warn("Wiremark icon bridge failed for ${documentFile.path}; rendering without src= icons.", t)
            emptyMap()
        }
    }

    /** Convenience: the map for [source] already JSON-encoded for the JS call. */
    fun buildIconJson(project: Project, documentFile: VirtualFile, source: String): String =
        IconPayload.toJson(buildIconMap(project, documentFile, source))

    /**
     * Resolve, security-check, read, and sanitize a single `src=` value. Returns
     * null (the entry is dropped) for any of: unresolvable path, escaping the
     * content roots, a directory/invalid file, an oversize file, an I/O error, or
     * no usable `<svg>` artwork. Must be called inside a read action.
     */
    private fun resolveOne(fileIndex: ProjectFileIndex, parent: VirtualFile, src: String): IconArt? {
        if (src.isEmpty()) return null
        // Resolve STRICTLY relative to the document's directory: findFileByRelativePath
        // walks child-by-child from `parent`, honoring `.`/`..` (a `..` off a symlink
        // uses the canonical parent), and -- crucially -- it does NOT interpret URLs
        // or absolute paths. A leading `/` is ignored (treated base-relative), unlike
        // VfsUtilCore.findRelativeFile which would resolve `/etc/passwd` against the
        // local FS root and bypass the document entirely. Any `..` that climbs out of
        // the project is still caught by the isInContent gate below.
        val file = parent.findFileByRelativePath(src) ?: return null
        if (!file.isValid || file.isDirectory) return null

        // THE traversal gate: the resolved file must live inside the project's
        // content roots. `../../../etc/passwd` resolves to a real file but lands
        // outside any content root, so it is rejected here.
        if (!fileIndex.isInContent(file)) {
            if (LOG.isDebugEnabled) {
                LOG.debug("Wiremark icon src=\"$src\" resolved outside project content; rejected (${file.path}).")
            }
            return null
        }

        if (file.length > MAX_ICON_BYTES) {
            if (LOG.isDebugEnabled) {
                LOG.debug("Wiremark icon src=\"$src\" is ${file.length} bytes (> $MAX_ICON_BYTES cap); skipped.")
            }
            return null
        }

        val text = try {
            // Char cap as defense in depth on top of the byte-length gate above.
            VfsUtilCore.loadText(file, MAX_ICON_BYTES.toInt())
        } catch (t: Throwable) {
            if (LOG.isDebugEnabled) {
                LOG.debug("Wiremark icon src=\"$src\" unreadable (${file.path}): ${t.message}")
            }
            return null
        }
        return IconSvg.fromSvg(text)
    }
}
