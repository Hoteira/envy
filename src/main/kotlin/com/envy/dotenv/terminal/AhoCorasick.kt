package com.envy.dotenv.terminal

/**
 * Aho-Corasick automaton for efficient multi-pattern string matching.
 * Immutable after construction — [scan] is thread-safe and stateless;
 * the caller supplies and receives the automaton state integer so that
 * streaming across PTY chunks works correctly.
 */
class AhoCorasick private constructor(
    private val goto: Array<IntArray>,
    private val fail: IntArray,
    private val output: Array<List<Int>>,
    private val patternLengths: IntArray,
    private val nonAsciiPatterns: List<String>
) {

    data class Match(val startOffset: Int, val endOffset: Int)
    data class ScanResult(val matches: List<Match>, val endState: Int)

    /**
     * Feeds [text] through the automaton starting from [initialState].
     * Match offsets are shifted by [baseOffset] so they map to the full
     * document rather than the chunk alone.  Returns all matches found
     * and the automaton state at the end (pass it as [initialState] for
     * the next chunk to handle secrets split across PTY frames).
     *
     * ASCII-only patterns ride the AC trie. Patterns containing chars
     * >= ALPHABET_SIZE (e.g. unicode passwords like "P@sswörd") are
     * scanned via a separate indexOf fallback so they aren't dropped.
     */
    fun scan(text: CharSequence, initialState: Int = 0, baseOffset: Int = 0): ScanResult {
        var state = initialState
        val matches = mutableListOf<Match>()

        for (i in text.indices) {
            val c = text[i].code
            if (c >= ALPHABET_SIZE) { state = 0; continue }

            while (state != 0 && goto[state][c] == -1) state = fail[state]
            state = if (goto[state][c] != -1) goto[state][c] else 0

            for (patIdx in output[state]) {
                val end = baseOffset + i + 1
                val start = end - patternLengths[patIdx]
                matches.add(Match(start, end))
            }
        }

        if (nonAsciiPatterns.isNotEmpty()) {
            val asString = text.toString()
            for (pattern in nonAsciiPatterns) {
                if (pattern.length > asString.length) continue
                var idx = 0
                while (true) {
                    val found = asString.indexOf(pattern, idx)
                    if (found < 0) break
                    matches.add(Match(baseOffset + found, baseOffset + found + pattern.length))
                    idx = found + 1
                }
            }
        }

        return ScanResult(matches, state)
    }

    companion object {
        private const val ALPHABET_SIZE = 128

        /**
         * Builds an automaton from [patterns]. ASCII-only patterns are
         * compiled into the AC trie; patterns containing non-ASCII chars
         * are kept as a fallback list scanned via [String.indexOf].
         * Returns null only if no non-empty patterns remain at all.
         */
        fun build(patterns: List<String>): AhoCorasick? {
            val nonEmpty = patterns.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) return null

            val asciiPatterns = mutableListOf<String>()
            val nonAsciiPatterns = mutableListOf<String>()
            for (p in nonEmpty) {
                if (p.all { it.code < ALPHABET_SIZE }) asciiPatterns.add(p)
                else nonAsciiPatterns.add(p)
            }

            val maxStates = asciiPatterns.sumOf { it.length } + 1
            val goto = Array(maxStates) { IntArray(ALPHABET_SIZE) { -1 } }
            val fail = IntArray(maxStates)
            val output = Array(maxStates) { mutableListOf<Int>() }
            val lengths = IntArray(asciiPatterns.size)
            var stateCount = 1 // state 0 = root

            // --- build trie ---
            for ((patIdx, pattern) in asciiPatterns.withIndex()) {
                lengths[patIdx] = pattern.length
                var cur = 0
                for (ch in pattern) {
                    val c = ch.code
                    if (goto[cur][c] == -1) goto[cur][c] = stateCount++
                    cur = goto[cur][c]
                }
                output[cur].add(patIdx)
            }

            // --- build failure links (BFS) ---
            val queue = ArrayDeque<Int>()
            for (c in 0 until ALPHABET_SIZE) {
                val s = goto[0][c]
                if (s != -1) { fail[s] = 0; queue.add(s) }
            }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for (c in 0 until ALPHABET_SIZE) {
                    val v = goto[u][c]
                    if (v == -1) continue
                    queue.add(v)
                    var f = fail[u]
                    while (f != 0 && goto[f][c] == -1) f = fail[f]
                    fail[v] = if (goto[f][c] != -1 && goto[f][c] != v) goto[f][c] else 0
                    output[v].addAll(output[fail[v]])
                }
            }

            return AhoCorasick(
                Array(stateCount) { goto[it] },
                fail.copyOf(stateCount),
                Array(stateCount) { output[it].toList() },
                lengths,
                nonAsciiPatterns.toList()
            )
        }
    }
}
