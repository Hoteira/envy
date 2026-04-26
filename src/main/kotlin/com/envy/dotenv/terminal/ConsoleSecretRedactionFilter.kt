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
                .filter { (k, v) -> SecretLeakInspection.isSecret(k, v) && v.length >= 4 }
                .map { it.second }
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
    override fun getDefaultFilters(project: Project): Array<InputFilter> {
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.consoleSecretRedaction) return emptyArray()
        if (!com.envy.dotenv.licensing.LicenseChecker.isPaidFeatureAvailable()) return emptyArray()
        return arrayOf(ConsoleSecretRedactionFilter(project))
    }
}

private class ConsoleSecretRedactionFilter(private val project: Project) : InputFilter {

    override fun applyFilter(
        text: String,
        contentType: ConsoleViewContentType
    ): List<JBPair<String, ConsoleViewContentType>>? {
        if (project.isDisposed) return null
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.consoleSecretRedaction) return null
        val svc = project.getService(ConsoleSecretAutomaton::class.java) ?: return null
        val automaton = svc.getAutomaton() ?: return null
        val result = automaton.scan(text)
        if (result.matches.isEmpty()) return null

        val merged = mergeRanges(result.matches)
        val sb = StringBuilder(text.length)
        var pos = 0
        for ((start, end) in merged) {
            if (start > pos) sb.append(text, pos, start)
            sb.append("***")
            pos = end
        }
        if (pos < text.length) sb.append(text, pos, text.length)

        return listOf(JBPair.create(sb.toString(), contentType))
    }

    private fun mergeRanges(matches: List<AhoCorasick.Match>): List<kotlin.Pair<Int, Int>> {
        if (matches.isEmpty()) return emptyList()
        val sorted = matches.sortedBy { it.startOffset }
        val merged = mutableListOf<kotlin.Pair<Int, Int>>()
        var curStart = sorted[0].startOffset
        var curEnd = sorted[0].endOffset
        for (i in 1 until sorted.size) {
            val m = sorted[i]
            if (m.startOffset <= curEnd) {
                curEnd = maxOf(curEnd, m.endOffset)
            } else {
                merged.add(curStart to curEnd)
                curStart = m.startOffset
                curEnd = m.endOffset
            }
        }
        merged.add(curStart to curEnd)
        return merged
    }
}
