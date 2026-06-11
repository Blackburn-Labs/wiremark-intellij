package dev.wiremark.intellij.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Hand-written highlighting lexer for the wiremark DSL.
 *
 * Mirrors the lexical shape of the real `@wiremark/core` lexer
 * (`src/lexer.js`) closely enough to color a buffer the way an author thinks
 * about it, but it is a *highlighting* lexer first: it NEVER throws and it
 * tokenizes ANY byte sequence to completion (malformed input, tabs in
 * indentation -- which core hard-rejects -- unicode, unterminated strings, a
 * `key=` with no value). Every offset of the input belongs to exactly one
 * token; there are no gaps and the stream always reaches `endOffset`.
 *
 * Grammar recap (per core):
 *  - The unit of structure is a line. A `//` run that is not inside a string
 *    opens an end-of-line comment; `#` is the anchor sigil, never a comment.
 *  - Leading spaces (core also forbids tabs; we don't) are indentation.
 *  - The first whitespace-delimited chunk after the indent is the component
 *    name; the rest are tokens.
 *  - A `"double-quoted"` run is one literal (whitespace inside it does not
 *    split); a backslash inside a string escapes the next char.
 *  - A bare chunk with an `=` at index > 0 (before any quote) is `key=value`,
 *    keyed on that first `=`; the value is the rest of the chunk verbatim, so a
 *    second `=` does NOT re-split it. Otherwise the chunk is a bare
 *    enum/flag/number/anchor.
 *
 * Implementation: a flat single-pass scanner over the buffer. Per-line state is
 * a tiny three-value mode -- [MODE_COMPONENT] (next chunk is the component
 * name), [MODE_TOKEN] (next chunk is a fresh token), [MODE_VALUE] (the cursor
 * sits just past an `=`, so the next chunk is a key's value and must not be
 * re-split on `=`). The mode is the lexer state, so highlighting can restart
 * from any token boundary, which the incremental highlighter requires.
 */
class WiremarkLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null

    /**
     * Line-position mode that [advance] reads to lex the *next* token; see the
     * MODE_* constants. This is forward-looking -- it is mutated mid-[advance] to
     * the mode the following token will start in -- so it is NOT what
     * [getState] returns.
     */
    private var mode: Int = MODE_COMPONENT

    /**
     * The value of [mode] at the start of the *current* token. The platform's
     * incremental highlighter records [getState] at a token boundary and later
     * passes it back to [start] to resume lexing from that token's offset, so
     * the returned state must describe the condition at [getTokenStart] -- not
     * the (already-advanced) [mode] for the next token.
     */
    private var tokenStartMode: Int = MODE_COMPONENT

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        this.mode = if (initialState in MODE_COMPONENT..MODE_VALUE) initialState else MODE_COMPONENT
        this.tokenStartMode = this.mode
        this.currentToken = null
        advance()
    }

    override fun getState(): Int = tokenStartMode

    override fun getTokenType(): IElementType? = currentToken

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        // Capture the mode the about-to-be-produced token starts in, before any
        // branch below mutates `mode` for the *next* token. getState() returns
        // this so the highlighter can restart at this token's offset.
        tokenStartMode = mode
        if (tokenStart >= endOffset) {
            currentToken = null
            return
        }

        val c = buffer[tokenStart]

        // Newline(s): one NEWLINE token per terminator; resets line state so the
        // next chunk is a component name again.
        if (c == '\n' || c == '\r') {
            tokenEnd = if (c == '\r' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '\n') {
                tokenStart + 2
            } else {
                tokenStart + 1
            }
            currentToken = WiremarkTokenTypes.NEWLINE
            mode = MODE_COMPONENT
            return
        }

        // Horizontal whitespace (spaces and tabs). Tabs are illegal in core's
        // indentation but a highlighting lexer must accept them, so we fold all
        // runs of space/tab into one WHITE_SPACE token. Whitespace does not
        // change the mode: it just separates chunks.
        if (c == ' ' || c == '\t') {
            var i = tokenStart + 1
            while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i++
            tokenEnd = i
            currentToken = WiremarkTokenTypes.WHITE_SPACE
            return
        }

        // A `=` reached as the start of a token is the key/value separator: emit
        // it as a single char and switch to value mode so the following chunk is
        // taken verbatim (not re-split on a further `=`).
        if (c == '=' && mode != MODE_COMPONENT) {
            tokenEnd = tokenStart + 1
            currentToken = WiremarkTokenTypes.EQUALS
            mode = MODE_VALUE
            return
        }

        // End-of-line comment: `//` outside a string. We are at the start of a
        // chunk (never inside a string here -- strings are consumed whole by
        // lexString, and scanChunkEnd stops a bare chunk before an unquoted
        // `//`), so a `//` reached here always opens a comment, matching core's
        // stripComment (a `//` outside a string ends the line).
        if (c == '/' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '/') {
            var i = tokenStart + 2
            while (i < endOffset && buffer[i] != '\n' && buffer[i] != '\r') i++
            tokenEnd = i
            currentToken = WiremarkTokenTypes.COMMENT
            return
        }

        when (mode) {
            MODE_COMPONENT -> {
                // The leading chunk of the line is the component name. Core would
                // reject a leading quote or `=`, but we just color the chunk.
                tokenEnd = scanChunkEnd(tokenStart)
                currentToken = WiremarkTokenTypes.COMPONENT
                mode = MODE_TOKEN
            }
            MODE_VALUE -> {
                // The value of a key=value pair: take the whole chunk and classify
                // it, never re-splitting on an interior `=` (core keeps `b=c` as
                // the value of `a=b=c`).
                val chunkEnd = scanChunkEnd(tokenStart)
                classifyValueChunk(tokenStart, chunkEnd)
                mode = MODE_TOKEN
            }
            else -> lexToken() // MODE_TOKEN
        }
    }

    /**
     * Emit the head of one non-leading, non-value chunk, classified the way
     * core's `classify` does:
     *  - starts with `"` -> a (possibly unterminated) string literal;
     *  - has an `=` before any quote at index > 0 -> the key part, emitted as
     *    ATTRIBUTE up to (not including) the `=`. The `=` and the value follow on
     *    subsequent [advance] calls (the `=` branch in [advance] flips to value
     *    mode);
     *  - otherwise -> the whole bare chunk, classified as anchor/number/identifier.
     */
    private fun lexToken() {
        val start = tokenStart
        if (buffer[start] == '"') {
            lexString(start)
            return
        }

        val chunkEnd = scanChunkEnd(start)
        val eq = indexOfEqualsBeforeQuote(start, chunkEnd)
        if (eq > start) {
            tokenEnd = eq
            currentToken = WiremarkTokenTypes.ATTRIBUTE
            // Stay in MODE_TOKEN; the next advance lands on `=` and that branch
            // emits EQUALS and switches to MODE_VALUE.
            return
        }

        classifyValueChunk(start, chunkEnd)
    }

    /**
     * Classify and emit a complete chunk `[start, chunkEnd)` that is a bare
     * token or a key's value: a string (leading `"`), an anchor (leading `#`),
     * a number, or otherwise a bare identifier.
     */
    private fun classifyValueChunk(start: Int, chunkEnd: Int) {
        val c = buffer[start]
        if (c == '"') {
            lexString(start)
            return
        }
        tokenEnd = chunkEnd
        currentToken = when {
            c == '#' -> WiremarkTokenTypes.ANCHOR
            isNumber(start, chunkEnd) -> WiremarkTokenTypes.NUMBER
            else -> WiremarkTokenTypes.IDENTIFIER
        }
    }

    /**
     * Scan a double-quoted literal beginning at [start] (`buffer[start] == '"'`).
     * Consumes through the matching close quote, treating `\` as escaping the
     * next char (matching core's `splitChunks`). If the quote is never closed,
     * the literal runs to the end of the line -- never past a newline, and never
     * throwing. The whole run (quotes included) is one STRING token.
     */
    private fun lexString(start: Int) {
        var i = start + 1
        while (i < endOffset) {
            val ch = buffer[i]
            if (ch == '\n' || ch == '\r') break // unterminated: stop at line end
            if (ch == '\\') {
                // Escape the next char -- but never across a line terminator or
                // the buffer end: a trailing `\` consumes only itself, so the
                // following newline stays its own token (core can't hit this
                // because it splits on newlines first; our single-buffer scan can).
                i += if (i + 1 < endOffset && buffer[i + 1] != '\n' && buffer[i + 1] != '\r') 2 else 1
                continue
            }
            if (ch == '"') {
                i += 1
                break
            }
            i += 1
        }
        tokenEnd = i
        currentToken = WiremarkTokenTypes.STRING
    }

    /**
     * Find the end of the chunk that starts at [from]: the first unescaped,
     * unquoted space/tab/newline, an unquoted `//` (which core's stripComment
     * treats as an end-of-line comment even mid-chunk), or buffer end. Mirrors
     * `splitChunks` + `stripComment`: a `"..."` run (with `\` escapes) does not
     * split and a `//` inside it is literal, so internal whitespace and slashes
     * are swallowed.
     */
    private fun scanChunkEnd(from: Int): Int {
        var i = from
        var inStr = false
        while (i < endOffset) {
            val ch = buffer[i]
            if (ch == '\n' || ch == '\r') break
            if (inStr && ch == '\\') {
                // Escape the next char, but never across a newline/buffer end (a
                // trailing `\` consumes only itself), so the chunk can't swallow
                // the line terminator.
                i += if (i + 1 < endOffset && buffer[i + 1] != '\n' && buffer[i + 1] != '\r') 2 else 1
                continue
            }
            if (ch == '"') {
                inStr = !inStr
                i += 1
                continue
            }
            if (!inStr) {
                if (ch == ' ' || ch == '\t') break
                if (ch == '/' && i + 1 < endOffset && buffer[i + 1] == '/') break
            }
            i += 1
        }
        if (i > endOffset) i = endOffset
        return i
    }

    /**
     * Within `[start, chunkEnd)`, return the index of the first `=` that occurs
     * before any `"` (so `label="a=b"` keys on the first `=`, and a leading-quote
     * chunk has no key), or -1.
     */
    private fun indexOfEqualsBeforeQuote(start: Int, chunkEnd: Int): Int {
        var i = start
        while (i < chunkEnd) {
            val ch = buffer[i]
            if (ch == '"') return -1
            if (ch == '=') return i
            i++
        }
        return -1
    }

    /**
     * True if `[start, end)` is a number: an optional leading `-`/`+`, then
     * digits with at most one decimal point, and nothing else. Empty or
     * sign-only runs are not numbers.
     */
    private fun isNumber(start: Int, end: Int): Boolean {
        if (end <= start) return false
        var i = start
        if (buffer[i] == '-' || buffer[i] == '+') i++
        if (i >= end) return false
        var sawDigit = false
        var sawDot = false
        while (i < end) {
            val ch = buffer[i]
            when {
                ch in '0'..'9' -> sawDigit = true
                ch == '.' && !sawDot -> sawDot = true
                else -> return false
            }
            i++
        }
        return sawDigit
    }

    companion object {
        /** Next chunk is the line's leading chunk (the component name). */
        private const val MODE_COMPONENT = 0

        /** Next chunk is a fresh token (string / bare / `key=value` head). */
        private const val MODE_TOKEN = 1

        /** Cursor sits just past an `=`: the next chunk is a value, taken verbatim. */
        private const val MODE_VALUE = 2
    }
}
