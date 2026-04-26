package com.envy.dotenv.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import com.envy.dotenv.inspections.SecretLeakInspection
import com.envy.dotenv.services.EnvFileService
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class TerminalSecretCensor(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TerminalSecretCensor::class.java)
        private val INVISIBLE_TEXT = TextAttributes(Color(0, 0, 0, 0), null, null, null, Font.PLAIN)
    }

    private class Session {
        var acState: Int = 0
        var scannedUpTo: Int = 0
        var needsFullRescan: Boolean = true
        var isRevealed: Boolean = false
        val activeHighlighters: MutableList<RangeHighlighter> = mutableListOf()
    }

    private val sessions = ConcurrentHashMap<Editor, Session>()
    private val pendingEditors = ConcurrentHashMap.newKeySet<Editor>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private var cachedSecretValues: List<String> = emptyList()
    private var cachedAutomaton: AhoCorasick? = null
    private val automatonLock = Any()
    @Volatile private var automatonDirty = true

    @Volatile private var disposed = false

    private val TOGGLE_MODIFIERS = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

    private val keyDispatcher = KeyEventDispatcher { e ->
        if (e.id == KeyEvent.KEY_PRESSED &&
            e.keyCode == KeyEvent.VK_X &&
            (e.modifiersEx and TOGGLE_MODIFIERS) == TOGGLE_MODIFIERS
        ) {
            toggleAll()
            true
        } else {
            false
        }
    }

    // must be initialized before terminalCheck
    private val terminalKeys: List<Key<Any?>> = run {
        try {
            val findKeyMethod = Key::class.java.getMethod("findKeyByName", String::class.java)
            TERMINAL_KEY_NAMES.mapNotNull { name ->
                @Suppress("UNCHECKED_CAST")
                findKeyMethod.invoke(null, name) as? Key<Any?>
            }
        } catch (e: Throwable) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            emptyList()
        }
    }

    private val terminalCheck: (Editor) -> Boolean = resolveTerminalCheck()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (disposed) return
                val editor = event.editor
                val ep = editor.project
                if (ep != null && ep != project) return

                val vf = FileDocumentManager.getInstance().getFile(editor.document)
                if (vf != null && vf.isInLocalFileSystem) return

                if (terminalCheck(editor)) {
                    track(editor)
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed && !editor.isDisposed && !sessions.containsKey(editor)) {
                            if (terminalCheck(editor)) {
                                track(editor)
                            }
                        }
                    }
                }
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                sessions.remove(event.editor)
            }
        }, this)

        for (editor in EditorFactory.getInstance().allEditors) {
            if (disposed) break
            val ep = editor.project
            if (ep != null && ep != project) continue
            if (terminalCheck(editor)) track(editor)
        }

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(com.envy.dotenv.services.EnvFileListener.TOPIC, object : com.envy.dotenv.services.EnvFileListener {
                override fun envFilesChanged() {
                    if (disposed) return
                    automatonDirty = true
                    for ((editor, session) in sessions) {
                        session.needsFullRescan = true
                        pendingEditors.add(editor)
                    }
                    if (pendingEditors.isNotEmpty()) {
                        alarm.cancelAllRequests()
                        alarm.addRequest(::processPending, 100)
                    }
                }
            })

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
        LOG.info("TerminalSecretCensor initialized for project: ${project.name}")
    }

    private fun resolveTerminalCheck(): (Editor) -> Boolean {
        val reflective = buildReflectiveCheck()
        return { editor ->
            (reflective != null && reflective(editor))
                    || checkUserDataKeys(editor)
                    || checkEditorKindOrFile(editor)
                    || isInTerminalComponent(editor)
        }
    }

    private fun checkEditorKindOrFile(editor: Editor): Boolean {
        try {
            val kind = editor.editorKind.name
            if (kind == "CONSOLE" || kind.contains("TERMINAL")) return true
        } catch (_: Throwable) {}

        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        return vf?.name?.contains("Terminal", ignoreCase = true) == true ||
               vf?.javaClass?.simpleName?.contains("Terminal", ignoreCase = true) == true
    }

    private fun buildReflectiveCheck(): ((Editor) -> Boolean)? {
        return try {
            val clazz = Class.forName(
                "org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils"
            )
            val instance = clazz.getField("INSTANCE").get(null)
            val methods = listOf(
                "isOutputModelEditor",
                "isOutputEditor",
                "isReworkedTerminalEditor",
                "isAlternateBufferModelEditor"
            ).mapNotNull { name ->
                try { clazz.getMethod(name, Editor::class.java) } catch (_: NoSuchMethodException) { null }
            }
            if (methods.isEmpty()) return null

            { editor: Editor ->
                try { methods.any { it.invoke(instance, editor) == true } } catch (_: Throwable) { false }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun checkUserDataKeys(editor: Editor): Boolean {
        for (key in terminalKeys) {
            if (editor.getUserData(key) != null) return true
        }
        return false
    }

    private fun isInTerminalComponent(editor: Editor): Boolean {
        return try {
            var comp: java.awt.Component? = editor.component
            var depth = 0
            while (comp != null && depth++ < 30) {
                val cls = comp.javaClass.name
                if (cls.contains("Terminal", ignoreCase = true) &&
                    (cls.contains("Panel", ignoreCase = true) ||
                            cls.contains("View", ignoreCase = true) ||
                            cls.contains("Widget", ignoreCase = true) ||
                            cls.contains("Output", ignoreCase = true) ||
                            cls.contains("Block", ignoreCase = true))
                ) return true
                comp = comp.parent
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun track(editor: Editor) {
        if (sessions.containsKey(editor)) return
        LOG.info("Tracking terminal editor ${editor.hashCode()}")
        val session = Session()
        sessions[editor] = session
        pendingEditors.add(editor)
        alarm.addRequest(::processPending, 10)

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed) return
                val s = sessions[editor] ?: return
                if (event.offset < s.scannedUpTo) {
                    s.needsFullRescan = true
                }
                pendingEditors.add(editor)
                alarm.cancelAllRequests()
                alarm.addRequest(::processPending, 50)
            }
        }, this)
    }

    fun toggleAll(): Int {
        for ((editor, session) in sessions) {
            session.isRevealed = !session.isRevealed
            session.needsFullRescan = true
            pendingEditors.add(editor)
        }
        if (pendingEditors.isNotEmpty()) {
            alarm.cancelAllRequests()
            alarm.addRequest(::processPending, 10)
        }
        return sessions.size
    }

    private fun getAutomaton(): Pair<AhoCorasick?, Boolean> {
        if (!automatonDirty) {
            synchronized(automatonLock) { return cachedAutomaton to false }
        }

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

        synchronized(automatonLock) {
            automatonDirty = false
            if (secrets == cachedSecretValues) return cachedAutomaton to false
            cachedSecretValues = secrets
            cachedAutomaton = AhoCorasick.build(secrets)
            LOG.info("Rebuilt Aho-Corasick with ${secrets.size} secret patterns")
            return cachedAutomaton to true
        }
    }

    private fun processPending() {
        if (disposed || project.isDisposed) return
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.terminalSecretCensor) return
        val editors = pendingEditors.toList()
        pendingEditors.clear()

        val (automaton, wasRebuilt) = getAutomaton()

        for (editor in editors) {
            if (editor.isDisposed) { sessions.remove(editor); continue }
            val session = sessions[editor] ?: continue

            session.activeHighlighters.removeAll { !it.isValid }

            if (automaton == null || session.isRevealed) {
                if (session.activeHighlighters.isNotEmpty()) clearHighlighters(editor, session)
                continue
            }

            val fullRescan = session.needsFullRescan || wasRebuilt

            val text = ApplicationManager.getApplication().runReadAction(Computable<CharSequence> {
                if (editor.isDisposed) "" else editor.document.immutableCharSequence
            })
            if (text.isEmpty()) continue

            val scanFrom: Int
            val initialState: Int
            if (fullRescan) {
                scanFrom = 0
                initialState = 0
            } else {
                scanFrom = session.scannedUpTo
                initialState = session.acState
                if (scanFrom >= text.length) continue
            }

            val chunk = text.subSequence(scanFrom, text.length)
            val result = automaton.scan(chunk, initialState, scanFrom)

            session.scannedUpTo = text.length
            session.acState = result.endState
            session.needsFullRescan = false

            val merged = mergeRanges(result.matches)
            applyHighlighters(editor, session, merged, fullRescan)
        }
    }

    private fun mergeRanges(matches: List<AhoCorasick.Match>): List<Pair<Int, Int>> {
        if (matches.isEmpty()) return emptyList()
        val sorted = matches.sortedBy { it.startOffset }
        val merged = mutableListOf<Pair<Int, Int>>()
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

    private fun applyHighlighters(
        editor: Editor,
        session: Session,
        ranges: List<Pair<Int, Int>>,
        clearExisting: Boolean
    ) {
        if (ranges.isEmpty() && !clearExisting) return

        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || disposed) return@invokeLater

            if (clearExisting) {
                for (h in session.activeHighlighters) {
                    if (h.isValid) editor.markupModel.removeHighlighter(h)
                }
                session.activeHighlighters.clear()
            }

            val docLen = editor.document.textLength
            val existingRanges = session.activeHighlighters
                .filter { it.isValid }
                .mapTo(HashSet()) { it.startOffset to it.endOffset }

            for ((start, end) in ranges) {
                if (start < 0 || end > docLen || start >= end) continue
                if (!existingRanges.add(start to end)) continue

                try {
                    val highlighter = editor.markupModel.addRangeHighlighter(
                        start, end,
                        HighlighterLayer.LAST + 1,
                        INVISIBLE_TEXT,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    highlighter.customRenderer = SecretCensorRenderer
                    session.activeHighlighters.add(highlighter)
                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                }
            }
        }
    }

    private fun clearHighlighters(editor: Editor, session: Session) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || disposed) return@invokeLater
            for (h in session.activeHighlighters) {
                if (h.isValid) editor.markupModel.removeHighlighter(h)
            }
            session.activeHighlighters.clear()
        }
    }

    fun clearAll() {
        for ((editor, session) in sessions) {
            clearHighlighters(editor, session)
        }
    }

    fun rescanAll() {
        for ((editor, session) in sessions) {
            session.needsFullRescan = true
            pendingEditors.add(editor)
        }
        if (pendingEditors.isNotEmpty()) {
            alarm.cancelAllRequests()
            alarm.addRequest(::processPending, 10)
        }
    }

    override fun dispose() {
        disposed = true
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher)
        sessions.clear()
        pendingEditors.clear()
    }
}

private object SecretCensorRenderer : CustomHighlighterRenderer {
    private var cachedBg: Color? = null
    private var cachedBgSource: Color? = null

    private fun opaqueBg(schemeBg: Color): Color {
        if (schemeBg === cachedBgSource) return cachedBg!!
        val bg = if (schemeBg.alpha < 255) Color(schemeBg.red, schemeBg.green, schemeBg.blue, 255) else schemeBg
        cachedBgSource = schemeBg
        cachedBg = bg
        return bg
    }

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val start = highlighter.startOffset
        val end = highlighter.endOffset
        if (start >= end || end > editor.document.textLength) return

        try {
            val startXY = editor.offsetToXY(start)
            val endXY = editor.offsetToXY(end)
            val bg = opaqueBg(editor.colorsScheme.defaultBackground)
            val lineHeight = editor.lineHeight

            g.color = bg
            if (startXY.y == endXY.y) {
                g.fillRect(startXY.x, startXY.y, endXY.x - startXY.x, lineHeight)
            } else {
                val w = editor.scrollingModel.visibleArea.width + editor.scrollingModel.visibleArea.x
                g.fillRect(startXY.x, startXY.y, w - startXY.x, lineHeight)
                var y = startXY.y + lineHeight
                while (y < endXY.y) {
                    g.fillRect(0, y, w, lineHeight)
                    y += lineHeight
                }
                if (endXY.x > 0) {
                    g.fillRect(0, endXY.y, endXY.x, lineHeight)
                }
            }

            g.color = editor.colorsScheme.defaultForeground
            g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g.drawString("***", startXY.x, startXY.y + g.fontMetrics.ascent)
        } catch (_: Exception) { }
    }
}

private val TERMINAL_KEY_NAMES = listOf(
    "IS_OUTPUT_MODEL_EDITOR_KEY",
    "IS_OUTPUT_EDITOR_KEY",
    "IS_ALTERNATE_BUFFER_MODEL_EDITOR_KEY",
    "TerminalOutputModel",
)

internal class TerminalSecretCensorInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.terminalSecretCensor) return
        DumbService.getInstance(project).runWhenSmart {
            project.getService(TerminalSecretCensor::class.java)
        }
    }
}
