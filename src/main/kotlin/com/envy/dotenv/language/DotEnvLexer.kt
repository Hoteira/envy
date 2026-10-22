package com.envy.dotenv.language

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.envy.dotenv.language.psi.DotEnvTypes

/**
 * Tokenises .env files with the semantics of the common loaders (dotenv, python-dotenv, Docker
 * Compose). This is the single source of truth for where keys and values start and end: the
 * editor (PSI) and EnvParser (used by completion, Env Diff and the terminal/console censor) both
 * read values from these tokens.
 *
 *  - `KEY=value # note`: an unquoted value ends before whitespace followed by `#`. A `#` that is not
 *    preceded by whitespace stays in the value (`pa#ss` is a valid password).
 *  - `"…"`, `'…'` and backtick values may span lines (PEM keys, JSON). If the closing quote never
 *    comes, the value ends at the end of the line instead of swallowing the rest of the file.
 *  - After a value only whitespace and a comment are expected; anything else is a bad character.
 *
 * States: 0 = start of line / key, 1 = after the separator (value expected), 2 = after the value.
 */
class DotEnvLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var currentPos: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null
    private var state: Int = STATE_KEY

    companion object {
        const val STATE_KEY = 0
        const val STATE_VALUE = 1
        const val STATE_AFTER_VALUE = 2
    }

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.currentPos = startOffset
        this.state = if (initialState in STATE_KEY..STATE_AFTER_VALUE) initialState else STATE_KEY
        advance()
    }

    override fun getState(): Int = state
    override fun getTokenType(): IElementType? = currentToken
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (currentPos >= endOffset) {
            currentToken = null
            return
        }

        tokenStart = currentPos
        val ch = buffer[currentPos]

        when {
            isNewline(ch) -> {
                currentPos++
                if (currentPos < endOffset && ch == '\r' && buffer[currentPos] == '\n') {
                    currentPos++
                }
                tokenEnd = currentPos
                currentToken = DotEnvTypes.NEWLINE
                state = STATE_KEY
            }

            state == STATE_AFTER_VALUE -> lexAfterValue(ch)

            state == STATE_VALUE -> lexValue(ch)

            ch == '#' -> {
                skipToEol()
                tokenEnd = currentPos
                currentToken = DotEnvTypes.COMMENT
            }

            isBlank(ch) -> {
                skipBlanks()
                tokenEnd = currentPos
                currentToken = com.intellij.psi.TokenType.WHITE_SPACE
            }

            ch == '=' || ch == ':' -> {
                currentPos++
                skipBlanks()
                tokenEnd = currentPos
                currentToken = DotEnvTypes.SEPARATOR
                state = STATE_VALUE
            }

            ch.isLetter() || ch == '_' || ch == '.' -> {
                val wordStart = currentPos
                while (currentPos < endOffset && isKeyChar(buffer[currentPos])) {
                    currentPos++
                }
                val word = buffer.subSequence(wordStart, currentPos).toString()
                tokenEnd = currentPos
                currentToken =
                    if (word == "export" && currentPos < endOffset && isBlank(buffer[currentPos])) DotEnvTypes.EXPORT
                    else DotEnvTypes.KEY
            }

            else -> {
                currentPos++
                tokenEnd = currentPos
                currentToken = com.intellij.psi.TokenType.BAD_CHARACTER
            }
        }
    }

    private fun lexValue(ch: Char) {
        // "KEY= # note": the separator already consumed the blanks, so a '#' right after them
        // starts a comment and the value is empty.
        if (ch == '#' && currentPos > startOffset && isBlank(buffer[currentPos - 1])) {
            state = STATE_AFTER_VALUE
            lexAfterValue(ch)
            return
        }

        if (ch == '"' || ch == '\'' || ch == '`') {
            currentPos = quotedValueEnd(currentPos, ch)
            tokenEnd = currentPos
            currentToken = DotEnvTypes.QUOTED_VALUE
            state = STATE_AFTER_VALUE
            return
        }

        // Unquoted: up to end of line or an inline comment (blank followed by '#'), trailing blanks excluded.
        var i = currentPos
        while (i < endOffset && !isNewline(buffer[i])) {
            if (buffer[i] == '#' && i > tokenStart && isBlank(buffer[i - 1])) break
            i++
        }
        var valueEnd = i
        while (valueEnd > tokenStart && isBlank(buffer[valueEnd - 1])) valueEnd--
        if (valueEnd == tokenStart) {
            // Only blanks before the comment; let the after-value state lex them.
            state = STATE_AFTER_VALUE
            lexAfterValue(ch)
            return
        }
        currentPos = valueEnd
        tokenEnd = currentPos
        currentToken = DotEnvTypes.VALUE
        state = STATE_AFTER_VALUE
    }

    private fun lexAfterValue(ch: Char) {
        when {
            isBlank(ch) -> {
                skipBlanks()
                tokenEnd = currentPos
                currentToken = com.intellij.psi.TokenType.WHITE_SPACE
            }
            ch == '#' -> {
                skipToEol()
                tokenEnd = currentPos
                currentToken = DotEnvTypes.COMMENT
            }
            else -> {
                // Text after a closing quote, e.g. KEY="a"b: not part of any loader's value.
                skipToEol()
                tokenEnd = currentPos
                currentToken = com.intellij.psi.TokenType.BAD_CHARACTER
            }
        }
    }

    /**
     * End offset (exclusive) of a quoted value starting at [openAt]. Backslash escapes the next
     * character. Spans lines when a closing quote exists; otherwise stops at the end of the line.
     */
    private fun quotedValueEnd(openAt: Int, quote: Char): Int {
        var i = openAt + 1
        while (i < endOffset) {
            val c = buffer[i]
            if (c == '\\' && i + 1 < endOffset) { i += 2; continue }
            if (c == quote) return i + 1
            i++
        }
        // Unterminated: keep it to this line.
        var j = openAt + 1
        while (j < endOffset && !isNewline(buffer[j])) j++
        return j
    }

    private fun skipToEol() {
        while (currentPos < endOffset && !isNewline(buffer[currentPos])) currentPos++
    }

    private fun skipBlanks() {
        while (currentPos < endOffset && isBlank(buffer[currentPos])) currentPos++
    }

    private fun isNewline(c: Char) = c == '\n' || c == '\r'
    private fun isBlank(c: Char) = c == ' ' || c == '\t'
    private fun isKeyChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'
}
