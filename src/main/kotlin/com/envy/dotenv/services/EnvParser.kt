package com.envy.dotenv.services

import com.envy.dotenv.language.DotEnvLexer
import com.envy.dotenv.language.psi.DotEnvTypes
import com.intellij.psi.TokenType

/**
 * Text-based .env parsing for code that has no PSI at hand (EnvFileService, and through it
 * completion, Env Diff and the terminal/console censor).
 *
 * It runs the same [DotEnvLexer] the editor uses and decodes values with [decodeValue], which
 * PsiEnvExtractor uses too, so every part of EnvY agrees on what a value is: multi-line quoted
 * values, inline comments and escapes included.
 */
object EnvParser {

    data class EnvEntry(
        val key: String,
        val value: String,
        val lineIndex: Int,
        val keyOffsetInFile: Int
    )

    data class ParseResult(
        val entries: List<EnvEntry>,
        val lineStartOffsets: IntArray
    )

    private const val BOM = '﻿'

    fun parse(text: CharSequence): ParseResult {
        val lineStarts = lineStartOffsets(text)
        val entries = mutableListOf<EnvEntry>()

        val lexer = DotEnvLexer()
        val start = if (text.isNotEmpty() && text[0] == BOM) 1 else 0
        lexer.start(text, start, text.length, DotEnvLexer.STATE_KEY)

        // One logical entry per line: [export] KEY <sep> [value] [# comment]
        var key: String? = null
        var keyOffset = -1
        var sawSeparator = false
        var rawValue: String? = null
        var broken = false

        fun flush() {
            val k = key
            if (k != null && sawSeparator && !broken) {
                entries.add(EnvEntry(k, decodeValue(rawValue ?: ""), lineIndexOf(lineStarts, keyOffset), keyOffset))
            }
            key = null; keyOffset = -1; sawSeparator = false; rawValue = null; broken = false
        }

        while (true) {
            val type = lexer.tokenType ?: break
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            when (type) {
                DotEnvTypes.NEWLINE -> flush()
                DotEnvTypes.EXPORT -> if (key != null || sawSeparator) broken = true
                DotEnvTypes.KEY -> {
                    // A second word before the separator means a command, not an assignment
                    // (direnv: "layout python3", "dotenv .env.local", "PATH_add bin").
                    if (key == null && !sawSeparator) { key = tokenText; keyOffset = lexer.tokenStart }
                    else if (!sawSeparator) broken = true
                }
                DotEnvTypes.SEPARATOR -> if (key != null && !sawSeparator) sawSeparator = true else broken = true
                DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> if (sawSeparator && rawValue == null) rawValue = tokenText
                TokenType.BAD_CHARACTER -> if (!sawSeparator) broken = true
                else -> {} // whitespace, comments
            }
            lexer.advance()
        }
        flush()

        return ParseResult(entries, lineStarts)
    }

    /**
     * The value a loader would produce from a value token: quotes removed, escapes processed in
     * double quotes, single quotes and backticks literal. Unquoted values are trimmed.
     */
    fun decodeValue(raw: String): String {
        val t = raw.trim()
        return when {
            t.length >= 2 && t.startsWith('"') && t.endsWith('"') -> processEscapes(t.substring(1, t.length - 1))
            t.length >= 2 && t.startsWith('\'') && t.endsWith('\'') -> t.substring(1, t.length - 1)
            t.length >= 2 && t.startsWith('`') && t.endsWith('`') -> t.substring(1, t.length - 1)
            else -> t
        }
    }

    fun processEscapes(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    else -> { sb.append(s[i]); i++ }
                }
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun lineStartOffsets(text: CharSequence): IntArray {
        val starts = mutableListOf(if (text.isNotEmpty() && text[0] == BOM) 1 else 0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                starts.add(i + 1)
            }
            i++
        }
        return starts.toIntArray()
    }

    private fun lineIndexOf(lineStarts: IntArray, offset: Int): Int {
        if (offset < 0) return 0
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}
