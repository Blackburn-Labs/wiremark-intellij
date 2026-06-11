package dev.wiremark.intellij.icons

/**
 * One `src=` icon declaration found in a wiremark source's `Icons` block: the
 * icon's name and the raw `src=` value exactly as the author wrote it (already
 * unquoted, exactly the string core hands back to the host `loadIcon` callback).
 */
data class IconSrcRef(val name: String, val src: String)

/**
 * Scans wiremark source text for `src=` entries inside the top-level `Icons`
 * block, so the editor can pre-read those files before pushing the source to the
 * JCEF preview (where `loadIcon` must be synchronous; see tasks/PLAN.md "Icons
 * with src=").
 *
 * This is a deliberately small, fault-tolerant scanner -- NOT a re-implementation
 * of core's lexer (`/tmp/package/src/lexer.js`). It runs on every debounce push
 * against text the author is in the middle of editing, so it must never throw and
 * must degrade gracefully on malformed input: core itself re-parses the same
 * source authoritatively and emits the real diagnostics. Our only job is to find
 * candidate `src=` paths to load; a missing or rejected one simply degrades to
 * core's `iconGlyph` placeholder + a soft diagnostic, which core already handles.
 *
 * Grammar it honors (mirroring the lexer just enough):
 *  - The `Icons` block is a top-level line `Icons` at indent 0 with no tokens of
 *    its own; its entries are the more-indented lines beneath it. The block ends
 *    at the next line whose indent returns to the `Icons` line's indent or less.
 *  - Indentation is spaces; a line that uses a leading tab is something the lexer
 *    rejects outright, so we treat the whole document as having no usable Icons
 *    block rather than guessing (core will surface the real error).
 *  - `//` starts an end-of-line comment, except inside a double-quoted run.
 *  - An entry is `Name <tokens...>`; among its tokens we look for `src=value`,
 *    where value may be `"quoted"` (with `\"`/`\\` escapes) or bare.
 */
object IconSrcScanner {

    /**
     * Every `src=` entry declared in the source's first top-level `Icons` block,
     * in document order. Empty when there is no such block (or it has none).
     *
     * Only the FIRST top-level `Icons` block is scanned: core merges all `Icons`
     * roots, but a duplicate icon name there is a soft warning where "the first
     * declaration wins", and v1 of this bridge keeps the surface area minimal. A
     * second block's `src=` icons still resolve through core to placeholders.
     */
    fun scan(source: String): List<IconSrcRef> {
        val lines = source.split("\n")
        var inIcons = false
        var iconsIndent = -1
        val refs = ArrayList<IconSrcRef>()

        for (rawLine in lines) {
            // A trailing '\r' (CRLF source) is whitespace; strip it so it can't
            // sneak into a bare src= value or the comment scan.
            val line = stripComment(rawLine.trimEnd('\r'))
            if (line.isBlank()) continue

            val indent = leadingSpaces(line)
            // A tab anywhere in the indentation is a hard lexer error; rather than
            // resolve files from a document core will reject, bail out entirely.
            if (hasLeadingTab(line)) return emptyList()

            val content = line.substring(indent).trimEnd()
            val name = headToken(content)

            if (!inIcons) {
                if (indent == 0 && name == "Icons") {
                    inIcons = true
                    iconsIndent = indent
                }
                continue
            }

            // Inside the block: a line at or below the Icons line's indent closes
            // it. (Equal indent = a sibling top-level node such as `Wireframe`.)
            if (indent <= iconsIndent) {
                break
            }

            // This is an Icons entry line. Pull its src= value, if any.
            val src = srcValueOf(content)
            if (src != null) refs.add(IconSrcRef(name, src))
        }
        return refs
    }

    /** Count of leading spaces (indentation is space-based in wiremark). */
    private fun leadingSpaces(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    /** True if the run of leading whitespace contains a tab (a lexer error). */
    private fun hasLeadingTab(line: String): Boolean {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
            if (line[i] == '\t') return true
            i++
        }
        return false
    }

    /**
     * Strip a `//` end-of-line comment, ignoring `//` inside a double-quoted run
     * (mirrors lexer.js stripComment). A backslash inside a string escapes the
     * next char. `#` is the anchor sigil, never a comment.
     */
    private fun stripComment(line: String): String {
        var inStr = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inStr && c == '\\') {
                i += 2
                continue
            }
            if (c == '"') {
                inStr = !inStr
                i++
                continue
            }
            if (!inStr && c == '/' && i + 1 < line.length && line[i + 1] == '/') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /**
     * The first whitespace-delimited chunk of a line's content -- the component
     * name. Quote-aware only insofar as a name never starts with a quote, so a
     * simple split on the first run of spaces is enough for the head token.
     */
    private fun headToken(content: String): String {
        var end = 0
        while (end < content.length && content[end] != ' ' && content[end] != '\t') end++
        return content.substring(0, end)
    }

    /**
     * Find a `src=` token in an entry line's content and return its value
     * (unquoted), or null if the line declares no `src=`. Splits the content into
     * quote-aware chunks exactly like the lexer so a `src="a b.svg"` path with a
     * space stays one token and a `//`-free quoted value is read whole.
     */
    private fun srcValueOf(content: String): String? {
        for (chunk in splitChunks(content)) {
            val eq = chunk.indexOf('=')
            if (eq <= 0) continue
            if (chunk.substring(0, eq) != "src") continue
            val rawVal = chunk.substring(eq + 1)
            return if (rawVal.startsWith("\"")) unquote(rawVal) else rawVal
        }
        return null
    }

    /**
     * Quote-aware split of a line's content into raw chunks: whitespace separates
     * chunks except inside a `"double-quoted"` run (mirrors lexer.js splitChunks).
     * An unterminated quote is tolerated here (the lexer would throw) -- we just
     * close the final chunk; core re-lexes and reports the real error.
     */
    private fun splitChunks(content: String): List<String> {
        val chunks = ArrayList<String>()
        val buf = StringBuilder()
        var inStr = false
        var started = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (inStr && c == '\\') {
                buf.append(c)
                if (i + 1 < content.length) {
                    buf.append(content[i + 1])
                    i++
                }
                started = true
                i++
                continue
            }
            if (c == '"') {
                inStr = !inStr
                buf.append(c)
                started = true
                i++
                continue
            }
            if (!inStr && (c == ' ' || c == '\t')) {
                if (started) {
                    chunks.add(buf.toString())
                    buf.setLength(0)
                    started = false
                }
                i++
                continue
            }
            buf.append(c)
            started = true
            i++
        }
        if (started) chunks.add(buf.toString())
        return chunks
    }

    /**
     * Drop the surrounding double quotes from a quoted chunk's value and unescape
     * `\"`/`\\` (mirrors lexer.js unquote). Tolerates a missing closing quote by
     * unescaping whatever follows the opening one.
     */
    private fun unquote(s: String): String {
        val end = if (s.length >= 2 && s.endsWith("\"")) s.length - 1 else s.length
        val inner = s.substring(1, end)
        val out = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length && (inner[i + 1] == '"' || inner[i + 1] == '\\')) {
                out.append(inner[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
