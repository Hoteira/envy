package com.envy.dotenv.services

/**
 * Shared .env parsing logic used by inspections, services, and completion.
 * Eliminates duplication across EnvFileService, DuplicateKeyInspection, and SecretLeakInspection.
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

    private val direnvCommands = setOf(
        "dotenv", "source_env", "source_up", "layout ", "use ", "PATH_add", "path_add", "watch_file", "log_"
    )

    private const val BOM = '\uFEFF'

    fun parse(text: CharSequence): ParseResult {
        val entries = mutableListOf<EnvEntry>()
        val lineStartOffsets = mutableListOf<Int>()

        var pos = if (text.isNotEmpty() && text[0] == BOM) 1 else 0
        var lineIndex = 0
        val length = text.length
        
        while (pos < length) {
            lineStartOffsets.add(pos)
            var end = pos
            while (end < length && text[end] != '\n' && text[end] != '\r') {
                end++
            }
            
            processLine(text, pos, end, lineIndex, pos, entries)
            
            pos = end
            if (pos < length && text[pos] == '\r') pos++
            if (pos < length && text[pos] == '\n') pos++
            lineIndex++
        }
        
        if (pos == length && length > 0 && (text[length - 1] == '\n' || text[length - 1] == '\r')) {
            lineStartOffsets.add(pos)
        }
        
        return ParseResult(entries, lineStartOffsets.toIntArray())
    }

    private fun processLine(text: CharSequence, start: Int, end: Int, index: Int, lineStartOffset: Int, entries: MutableList<EnvEntry>) {
        var s = start
        while (s < end && text[s].isWhitespace()) s++
        var e = end
        while (e > s && text[e - 1].isWhitespace()) e--
        
        if (s == e || text[s] == '#') return
        
        val trimmed = text.subSequence(s, e).toString()
        val effective = when {
            trimmed.startsWith("export ") -> trimmed.removePrefix("export ").trim()
            direnvCommands.any { trimmed.startsWith(it) } -> return
            else -> trimmed
        }
        
        val sepIndex = effective.indexOfFirst { it == '=' || it == ':' }
        if (sepIndex <= 0) return
        
        val key = effective.substring(0, sepIndex).trim()
        val rawValue = effective.substring(sepIndex + 1).trim()
        val value = when {
            rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"') ->
                processEscapes(rawValue.substring(1, rawValue.length - 1))
            rawValue.length >= 2 && rawValue.startsWith('\'') && rawValue.endsWith('\'') ->
                rawValue.substring(1, rawValue.length - 1)
            rawValue.length >= 2 && rawValue.startsWith('`') && rawValue.endsWith('`') ->
                rawValue.substring(1, rawValue.length - 1)
            else -> rawValue
        }
            
        val leadingWhitespace = s - start
        val exportOffset = if (trimmed.startsWith("export ")) {
            "export ".length + (trimmed.removePrefix("export ").length - trimmed.removePrefix("export ").trimStart().length)
        } else {
            0
        }
        
        entries.add(EnvEntry(key, value, index, lineStartOffset + leadingWhitespace + exportOffset))
    }

    private fun processEscapes(s: String): String {
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
}
