package com.envy.dotenv.terminal

import com.intellij.execution.filters.ConsoleInputFilterProvider
import com.intellij.execution.filters.InputFilter
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Pair as JBPair
import com.envy.dotenv.inspections.SecretLeakInspection
import com.envy.dotenv.services.EnvFileListener
import com.envy.dotenv.services.EnvFileService

@Service(Service.Level.PROJECT)
class ConsoleSecretAutomaton(private val project: Project) : Disposable {
    private var cachedSecrets: List<String> = emptyList()
    private var cachedAutomaton: AhoCorasick? = null
    @Volatile var dirty = true

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EnvFileListener.TOPIC, object : EnvFileListener {
                override fun envFilesChanged() {
                    dirty = true
                }
            })
    }

    fun getAutomaton(): AhoCorasick? {
        if (!dirty) return cachedAutomaton

        val secrets = ApplicationManager.getApplication().runReadAction(Computable {
            if (project.isDisposed) return@Computable emptyList<String>()
            val svc = project.getService(EnvFileService::class.java)
                ?: return@Computable emptyList<String>()
            svc.getAllParsedEntries()
                .flatMap { (k, v) -> SecretLeakInspection.censorTargets(k, v) }
                .distinct()
                .sorted()
        })

        if (secrets == cachedSecrets) {
            dirty = false
            return cachedAutomaton
        }
        cachedSecrets = secrets
        cachedAutomaton = AhoCorasick.build(secrets)
        dirty = false
        return cachedAutomaton
    }

    override fun dispose() {}
}

class ConsoleSecretRedactionProvider : ConsoleInputFilterProvider {
    // The filter is attached to every console and checks the license per chunk (a cached boolean),
    // so a trial started mid-session also covers consoles that were already open.
    override fun getDefaultFilters(project: Project): Array<InputFilter> {
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.consoleSecretRedaction) return emptyArray()
        return arrayOf(ConsoleSecretRedactionFilter(project))
    }
}

private class ConsoleSecretRedactionFilter(private val project: Project) : InputFilter {

    private val redactor = StreamingRedactor()

    override fun applyFilter(
        text: String,
        contentType: ConsoleViewContentType
    ): List<JBPair<String, ConsoleViewContentType>>? {
        if (project.isDisposed) return null
        val enabled = com.envy.dotenv.settings.EnvySettings.getInstance().state.consoleSecretRedaction &&
            com.envy.dotenv.licensing.LicenseChecker.isPaidFeatureAvailable()
        val automaton = if (enabled) project.getService(ConsoleSecretAutomaton::class.java)?.getAutomaton() else null

        // Never hold back what the user types: that would delay their own echo.
        val canHold = contentType != ConsoleViewContentType.USER_INPUT
        val out = redactor.process(contentType, text, automaton, canHold) ?: return null
        return listOf(JBPair.create(out, contentType))
    }
}

/**
 * Masks secrets in a stream that arrives in arbitrary chunks (one stream per content type, since
 * stdout and stderr interleave). Two things keep a secret split across chunks from leaking:
 *  - the automaton state is carried from one chunk to the next, so the rest of a split secret is
 *    still recognised and masked;
 *  - when a chunk ends with 4+ characters that could begin a secret, that tail is held back and
 *    printed with the next chunk, so not even the start of the secret shows.
 * Shorter tails are printed straight away: holding single characters would stall interactive
 * prompts. A held tail is lost only if the process exits mid-secret without a final newline.
 */
internal class StreamingRedactor {

    private class StreamState {
        var held: String = ""
        var carryState: Int = 0
    }

    private val streams = HashMap<Any, StreamState>()

    /** Returns the text to print, or null when [text] can be printed unchanged. */
    fun process(stream: Any, text: String, automaton: AhoCorasick?, canHold: Boolean): String? = synchronized(streams) {
        processLocked(stream, text, automaton, canHold)
    }

    private fun processLocked(stream: Any, text: String, automaton: AhoCorasick?, canHold: Boolean): String? {
        val state = streams.getOrPut(stream) { StreamState() }
        val input = state.held + text
        val hadHeld = state.held.isNotEmpty()
        state.held = ""

        if (automaton == null) {
            state.carryState = 0
            return if (hadHeld) input else null
        }

        val result = automaton.scan(input, state.carryState, 0)

        var cut = input.length
        val pending = automaton.pendingPrefixLength(result.endState)
        if (canHold && pending >= MIN_HOLD && pending <= input.length) {
            cut = input.length - pending
            // Never split a match: hold it whole and re-find it in the next chunk.
            for (m in result.matches) if (m.endOffset > cut) cut = minOf(cut, m.startOffset)
            if (cut < 0) cut = input.length // the match began in text that is already printed
        }

        if (cut < input.length) {
            state.held = input.substring(cut)
            state.carryState = 0 // the held text is re-scanned from a clean state
        } else {
            state.carryState = result.endState
        }

        val ranges = mergeRanges(
            result.matches.filter { it.endOffset <= cut }.map { maxOf(0, it.startOffset) to it.endOffset }
        )
        if (ranges.isEmpty()) {
            return if (!hadHeld && cut == input.length) null else input.substring(0, cut)
        }

        val sb = StringBuilder(cut)
        var pos = 0
        for ((start, end) in ranges) {
            if (start > pos) sb.append(input, pos, start)
            sb.append("***")
            pos = maxOf(pos, end)
        }
        if (pos < cut) sb.append(input, pos, cut)
        return sb.toString()
    }

    private fun mergeRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) {
                curEnd = maxOf(curEnd, e)
            } else {
                merged.add(curStart to curEnd)
                curStart = s
                curEnd = e
            }
        }
        merged.add(curStart to curEnd)
        return merged
    }

    companion object {
        const val MIN_HOLD = 4
    }
}
