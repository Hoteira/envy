package com.envy.dotenv.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import java.awt.datatransfer.StringSelection
import com.envy.dotenv.inspections.SecretLeakInspection
import com.envy.dotenv.licensing.LicenseChecker
import com.envy.dotenv.licensing.LicenseStateListener
import com.envy.dotenv.services.EnvFileService
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class TerminalSecretCensor(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TerminalSecretCensor::class.java)
        private val INVISIBLE_TEXT = TextAttributes(Color(0, 0, 0, 0), null, null, null, Font.PLAIN)
        internal val SESSION_KEY = Key.create<Session>("envy.terminalSecretCensor.session")

        /** Must match the action id in plugin.xml; its keymap shortcut drives the in-terminal toggle. */
        private const val TOGGLE_ACTION_ID = "EnvY.ToggleTerminalSecrets"
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        private const val OUTPUT_SCAN_INTERVAL_MS = 30
    }

    internal class Session {
        // Written by the scanner (pooled thread) and read by the toggle/listeners.
        @Volatile var acState: Int = 0
        @Volatile var scannedUpTo: Int = 0
        @Volatile var needsFullRescan: Boolean = true
        @Volatile var isRevealed: Boolean = false
        /** Cheap flag the scanner can read without touching highlighters. Only written on the EDT. */
        @Volatile var hasHighlighters: Boolean = false
        /** EDT only: read and modified exclusively inside invokeLater blocks and the copy handler. */
        val activeHighlighters: MutableList<RangeHighlighter> = mutableListOf()
    }

    private val sessions = ConcurrentHashMap<Editor, Session>()
    private val pendingEditors = ConcurrentHashMap.newKeySet<Editor>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    // Declared before init: init tracks existing terminals, which schedules a scan.
    private val scanScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private var cachedSecretValues: List<String> = emptyList()
    private var cachedAutomaton: AhoCorasick? = null
    private val automatonLock = Any()
    @Volatile private var automatonDirty = true

    @Volatile private var disposed = false

    /**
     * The reworked terminal swallows action shortcuts, so the toggle is caught here instead. It only
     * reacts when keyboard focus is inside one of THIS project's censored terminals, uses whatever
     * shortcut the user bound to the toggle action in their keymap, and lets every other key event
     * through untouched (so other projects' dispatchers and the IDE still see it).
     */
    private val keyDispatcher = KeyEventDispatcher { e ->
        if (disposed || e.id != KeyEvent.KEY_PRESSED || sessions.isEmpty()) return@KeyEventDispatcher false
        if (!isToggleShortcut(e)) return@KeyEventDispatcher false
        val focused = e.component
            ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?: return@KeyEventDispatcher false
        if (!isInOurTerminal(focused)) return@KeyEventDispatcher false
        toggleAll()
        true
    }

    private fun isInOurTerminal(focused: java.awt.Component): Boolean {
        if (sessions.keys.any { !it.isDisposed && SwingUtilities.isDescendingFrom(focused, it.component) }) return true
        // Fallback: focus can sit on a terminal wrapper rather than the tracked output editor.
        if (project.isDisposed) return false
        val terminalWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID) ?: return false
        return SwingUtilities.isDescendingFrom(focused, terminalWindow.component)
    }

    private fun isToggleShortcut(e: KeyEvent): Boolean {
        val stroke = KeyStroke.getKeyStrokeForEvent(e)
        val keymap = KeymapManager.getInstance()?.activeKeymap ?: return false
        return keymap.getShortcuts(TOGGLE_ACTION_ID).any {
            it is KeyboardShortcut && it.secondKeyStroke == null && it.firstKeyStroke == stroke
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
                val ed = event.editor
                if (sessions.remove(ed) != null) ed.putUserData(SESSION_KEY, null)
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
                    if (pendingEditors.isNotEmpty()) scheduleScan(100)
                }
            })

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
        ClassicTerminalNotice.maybeShow(project)
        LOG.info("TerminalSecretCensor initialized for project: ${project.name}")
    }

    private fun resolveTerminalCheck(): (Editor) -> Boolean {
        val reflective = buildReflectiveCheck()
        return { editor ->
            checkEditorKindOrFile(editor)
                    || checkUserDataKeys(editor)
                    || (reflective != null && reflective(editor))
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
        if (!LicenseChecker.isPaidFeatureAvailable()) return
        LOG.info("Tracking terminal editor ${editor.hashCode()}")
        val session = Session()
        sessions[editor] = session
        editor.putUserData(SESSION_KEY, session)
        pendingEditors.add(editor)
        scheduleScan(10)

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed) return
                val s = sessions[editor] ?: return
                if (event.offset < s.scannedUpTo) {
                    s.needsFullRescan = true
                }
                pendingEditors.add(editor)
                // Throttle, don't debounce: with continuous output (logs, docker compose up) a
                // restart-on-every-chunk timer would never fire and secrets would stay visible.
                scheduleScan(OUTPUT_SCAN_INTERVAL_MS)
            }
        }, this)
    }

    /** At most one scan queued at a time; later requests ride on it instead of postponing it. */
    private fun scheduleScan(delayMs: Int) {
        if (disposed) return
        if (scanScheduled.compareAndSet(false, true)) alarm.addRequest(::runScheduledScan, delayMs)
    }

    private fun runScheduledScan() {
        scanScheduled.set(false)
        processPending()
    }

    fun toggleAll(): Int {
        for ((editor, session) in sessions) {
            session.isRevealed = !session.isRevealed
            session.needsFullRescan = true
            pendingEditors.add(editor)
        }
        if (pendingEditors.isNotEmpty()) scheduleScan(10)
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
                .flatMap { (k, v) -> SecretLeakInspection.censorTargets(k, v) }
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
        if (!LicenseChecker.isPaidFeatureAvailable()) {
            if (sessions.isNotEmpty()) clearAll()
            return
        }
        val editors = mutableListOf<Editor>()
        val iter = pendingEditors.iterator()
        while (iter.hasNext()) { editors += iter.next(); iter.remove() } // an editor re-added meanwhile stays queued

        val (automaton, wasRebuilt) = getAutomaton()

        for (editor in editors) {
            if (editor.isDisposed) { sessions.remove(editor); continue }
            val session = sessions[editor] ?: continue

            // Highlighters are EDT-only; this thread just computes ranges and hands them over.
            if (automaton == null || session.isRevealed) {
                if (session.hasHighlighters) clearHighlighters(editor, session)
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
            } else {
                session.activeHighlighters.removeAll { !it.isValid }
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
            session.hasHighlighters = session.activeHighlighters.isNotEmpty()
        }
    }

    private fun clearHighlighters(editor: Editor, session: Session) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || disposed) return@invokeLater
            for (h in session.activeHighlighters) {
                if (h.isValid) editor.markupModel.removeHighlighter(h)
            }
            session.activeHighlighters.clear()
            session.hasHighlighters = false
        }
    }

    fun clearAll() {
        for ((editor, session) in sessions) {
            clearHighlighters(editor, session)
        }
    }

    /**
     * Starts censoring terminals that were opened while EnvY was unlicensed (track() skips them),
     * then rescans everything. Called on the EDT when the license becomes valid mid-session.
     */
    fun retrackAllEditors() {
        if (disposed) return
        for (editor in EditorFactory.getInstance().allEditors) {
            val ep = editor.project
            if (ep != null && ep != project) continue
            if (!editor.isDisposed && terminalCheck(editor)) track(editor)
        }
        rescanAll()
    }

    fun rescanAll() {
        for ((editor, session) in sessions) {
            session.needsFullRescan = true
            pendingEditors.add(editor)
        }
        if (pendingEditors.isNotEmpty()) scheduleScan(10)
    }

    override fun dispose() {
        disposed = true
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher)
        for (editor in sessions.keys) {
            try { editor.putUserData(SESSION_KEY, null) } catch (_: Throwable) {}
        }
        sessions.clear()
        pendingEditors.clear()
    }
}

/**
 * Wraps the EditorCopy handler so that selecting text containing a censored secret in a tracked
 * terminal editor places "***" on the clipboard instead of the secret. Editors not tagged with
 * SESSION_KEY fall through to the original handler unchanged.
 *
 * Registered through the `editorActionHandler` extension point in plugin.xml (never through
 * EditorActionManager.setActionHandler), so the platform restores the original handler when EnvY
 * is disabled, updated or uninstalled without a restart.
 */
internal class RedactingCopyHandler(private val delegate: EditorActionHandler) : EditorActionHandler() {

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        delegate.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val session = editor.getUserData(TerminalSecretCensor.SESSION_KEY)

        val sm = editor.selectionModel
        if (session == null || session.isRevealed || !sm.hasSelection()) {
            delegate.execute(editor, caret, dataContext)
            return
        }

        val selStart = sm.selectionStart
        val selEnd = sm.selectionEnd
        val doc = editor.document.immutableCharSequence
        if (selStart < 0 || selEnd > doc.length || selStart >= selEnd) {
            delegate.execute(editor, caret, dataContext)
            return
        }

        val overlapping = session.activeHighlighters
            .asSequence()
            .filter { it.isValid && it.startOffset < selEnd && it.endOffset > selStart }
            .map { maxOf(it.startOffset, selStart) to minOf(it.endOffset, selEnd) }
            .sortedBy { it.first }
            .toList()

        if (overlapping.isEmpty()) {
            delegate.execute(editor, caret, dataContext)
            return
        }

        val merged = mutableListOf<Pair<Int, Int>>()
        for ((s, e) in overlapping) {
            val last = merged.lastOrNull()
            if (last != null && s <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, e)
            } else {
                merged.add(s to e)
            }
        }

        val sb = StringBuilder(selEnd - selStart)
        var pos = selStart
        for ((s, e) in merged) {
            if (s > pos) sb.append(doc, pos, s)
            sb.append("***")
            pos = e
        }
        if (pos < selEnd) sb.append(doc, pos, selEnd)

        try {
            CopyPasteManager.getInstance().setContents(StringSelection(sb.toString()))
        } catch (_: Throwable) {
            delegate.execute(editor, caret, dataContext)
        }
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
        if (!LicenseChecker.isPaidFeatureAvailable()) return
        DumbService.getInstance(project).runWhenSmart {
            project.getService(TerminalSecretCensor::class.java)
        }
    }
}

/**
 * Applies a license change without an IDE restart. When a license or trial becomes valid it creates
 * the censor for every open project (startup skips it for unlicensed users) and starts tracking
 * terminals that were opened while unlicensed. When the license goes away it removes the masks.
 * Registered under <applicationListeners> in plugin.xml.
 */
internal class TerminalLicenseListener : LicenseStateListener {
    override fun licenseStateChanged(licensed: Boolean) {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            if (!licensed) {
                project.getServiceIfCreated(TerminalSecretCensor::class.java)?.clearAll()
                continue
            }
            if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.terminalSecretCensor) continue
            DumbService.getInstance(project).runWhenSmart {
                if (!project.isDisposed) {
                    project.getService(TerminalSecretCensor::class.java)?.retrackAllEditors()
                    ClassicTerminalNotice.maybeShow(project)
                }
            }
        }
    }
}

/**
 * Terminal masking works on the editor-based terminal (Reworked 2025, the default since 2025.2).
 * Users who switched back to the Classic engine would pay for a feature that silently does nothing
 * there, so tell licensed users once per IDE session how to fix it.
 */
internal object ClassicTerminalNotice {
    private val shown = java.util.concurrent.atomic.AtomicBoolean(false)

    fun maybeShow(project: Project) {
        if (shown.get() || project.isDisposed) return
        if (!LicenseChecker.isLicenseDetermined() || !LicenseChecker.isPaidFeatureAvailableStrict()) return
        if (!isClassicEngine()) return
        if (!shown.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("EnvY")
                .createNotification(
                    "EnvY can't mask secrets in the Classic terminal",
                    "Terminal masking works with the default terminal engine (Reworked 2025). " +
                        "Run console and clipboard protection work either way.",
                    com.intellij.notification.NotificationType.WARNING
                )
                .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Open terminal settings") {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "Terminal")
                })
                .notify(project)
        }
    }

    /** Reads the terminal engine setting by reflection: the option class isn't stable API. */
    private fun isClassicEngine(): Boolean = try {
        val optionsClass = Class.forName("org.jetbrains.plugins.terminal.TerminalOptionsProvider")
        val companion = optionsClass.getField("Companion").get(null)
        val options = companion.javaClass.getMethod("getInstance").invoke(companion)
        val engine = options.javaClass.getMethod("getTerminalEngine").invoke(options)
        engine?.toString()?.contains("CLASSIC", ignoreCase = true) == true
    } catch (_: Throwable) {
        false
    }
}
