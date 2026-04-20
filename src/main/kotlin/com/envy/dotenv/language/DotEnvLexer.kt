package com.envy.dotenv.language

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.envy.dotenv.language.psi.DotEnvTypes

class DotEnvLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var currentPos: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null
    private var afterSeparator: Boolean = false  // after = sign?

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.currentPos = startOffset
        this.afterSeparator = initialState == 1
        advance()
    }

    override fun getState(): Int = if (afterSeparator) 1 else 0
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
            ch == '\n' || ch == '\r' -> {
                currentPos++
                if (currentPos < endOffset && ch == '\r' && buffer[currentPos] == '\n') {
                    currentPos++
                }
                tokenEnd = currentPos
                currentToken = DotEnvTypes.NEWLINE
                afterSeparator = false
            }

            ch == '#' && !afterSeparator -> {
                skipToEol()
                tokenEnd = currentPos
                currentToken = DotEnvTypes.COMMENT
            }

            (ch == ' ' || ch == '\t') && !afterSeparator -> {
                while (currentPos < endOffset && (buffer[currentPos] == ' ' || buffer[currentPos] == '\t')) {
                    currentPos++
                }
                tokenEnd = currentPos
                currentToken = com.intellij.psi.TokenType.WHITE_SPACE
            }

            (ch == '=' || ch == ':') && !afterSeparator -> {
                currentPos++
                while (currentPos < endOffset && (buffer[currentPos] == ' ' || buffer[currentPos] == '\t')) {
                    currentPos++
                }
                tokenEnd = currentPos
                currentToken = DotEnvTypes.SEPARATOR
                afterSeparator = true
            }

            afterSeparator -> {
                if (ch == '"' || ch == '\'' || ch == '`') {
                    val quote = ch
                    val allowMultiline = quote == '"'
                    currentPos++
                    while (currentPos < endOffset && buffer[currentPos] != quote) {
                        if (buffer[currentPos] == '\\' && currentPos + 1 < endOffset) currentPos++
                        if (!allowMultiline && (buffer[currentPos] == '\n' || buffer[currentPos] == '\r')) break
                        currentPos++
                    }
                    if (currentPos < endOffset && buffer[currentPos] == quote) currentPos++
                    tokenEnd = currentPos
                    currentToken = DotEnvTypes.QUOTED_VALUE
                } else {
                    skipToEol()
                    tokenEnd = currentPos
                    currentToken = DotEnvTypes.VALUE
                }
            }

            ch.isLetter() || ch == '_' || ch == '.' -> {
                val wordStart = currentPos
                while (currentPos < endOffset && (buffer[currentPos].isLetterOrDigit() || buffer[currentPos] == '_' || buffer[currentPos] == '.')) {
                    currentPos++
                }
                val word = buffer.subSequence(wordStart, currentPos)

                if (word == "export" && currentPos < endOffset && (buffer[currentPos] == ' ' || buffer[currentPos] == '\t')) {
                    tokenEnd = currentPos
                    currentToken = DotEnvTypes.EXPORT
                } else {
                    tokenEnd = currentPos
                    currentToken = DotEnvTypes.KEY
                }
            }

            else -> {
                currentPos++
                tokenEnd = currentPos
                currentToken = com.intellij.psi.TokenType.BAD_CHARACTER
            }
        }
    }

    private fun skipToEol() {
        while (currentPos < endOffset && buffer[currentPos] != '\n' && buffer[currentPos] != '\r') {
            currentPos++
        }
    }
}