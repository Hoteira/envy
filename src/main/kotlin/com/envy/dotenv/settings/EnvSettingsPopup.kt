package com.envy.dotenv.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import com.envy.dotenv.language.DotEnvFileType
import com.envy.dotenv.licensing.LicenseChecker
import com.envy.dotenv.terminal.TerminalSecretCensor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

object EnvSettingsPopup {

    private enum class FeatureId {
        SYNTAX, DUPLICATE, PRESENTATION, AUTOCOMPLETE,
        TERMINAL_CENSOR, CONSOLE_REDACTION,
        SECRET_LEAK, GHOST_COMPLETION, SOPS
    }

    private data class FeatureRow(
        val id: FeatureId,
        val name: String,
        val isPro: Boolean,
        val getter: (EnvySettings.State) -> Boolean,
        val setter: (EnvySettings.State, Boolean) -> Unit
    )

    private val features = listOf(
        FeatureRow(FeatureId.SYNTAX, "Syntax Highlighting", false, { it.syntaxHighlighting }, { s, v -> s.syntaxHighlighting = v }),
        FeatureRow(FeatureId.DUPLICATE, "Duplicate Key Detection", false, { it.duplicateKeyDetection }, { s, v -> s.duplicateKeyDetection = v }),
        FeatureRow(FeatureId.PRESENTATION, "Presentation Mode", false, { it.presentationMode }, { s, v -> s.presentationMode = v }),
        FeatureRow(FeatureId.AUTOCOMPLETE, "Env Var Autocomplete", false, { it.envVarAutocomplete }, { s, v -> s.envVarAutocomplete = v }),
        FeatureRow(FeatureId.TERMINAL_CENSOR, "Terminal Secret Censor (Ctrl+Alt+Shift+X)", false, { it.terminalSecretCensor }, { s, v -> s.terminalSecretCensor = v }),
        FeatureRow(FeatureId.CONSOLE_REDACTION, "Console Secret Redaction", true, { it.consoleSecretRedaction }, { s, v -> s.consoleSecretRedaction = v }),
        FeatureRow(FeatureId.SECRET_LEAK, "Secret Leak Detection", true, { it.secretLeakDetection }, { s, v -> s.secretLeakDetection = v }),
        FeatureRow(FeatureId.GHOST_COMPLETION, "Inline Ghost Completion", true, { it.inlineGhostCompletion }, { s, v -> s.inlineGhostCompletion = v }),
        FeatureRow(FeatureId.SOPS, "SOPS Integration", true, { it.sopsIntegration }, { s, v -> s.sopsIntegration = v })
    )

    fun show(project: Project, owner: JComponent) {
        val settings = EnvySettings.getInstance()
        val state = settings.state
        val hasPro = LicenseChecker.isPaidFeatureAvailable()

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8, 12)

        val titleLabel = JLabel("EnvY Settings")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, titleLabel.font.size + 2f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titleLabel.border = JBUI.Borders.emptyBottom(8)
        panel.add(titleLabel)

        panel.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 1) })
        panel.add(Box.createVerticalStrut(6))

        for (feature in features) {
            val row = JPanel(BorderLayout(8, 0))
            row.alignmentX = Component.LEFT_ALIGNMENT
            row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            row.isOpaque = false

            val proLocked = feature.isPro && !hasPro

            val toggle = ToggleSwitch(feature.getter(state))
            if (proLocked) {
                toggle.locked = true
            }
            toggle.onToggle = { on ->
                if (!proLocked) {
                    feature.setter(state, on)
                    applyToggle(project, feature.id, on)
                }
            }

            row.add(toggle, BorderLayout.WEST)

            val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            labelPanel.isOpaque = false

            val nameLabel = JLabel(feature.name)
            if (proLocked) {
                nameLabel.foreground = UIManager.getColor("Label.disabledForeground")
                    ?: nameLabel.foreground.let { Color(it.red, it.green, it.blue, 128) }
            }
            labelPanel.add(nameLabel)

            if (feature.isPro) {
                labelPanel.add(ProBadge())
            }

            row.add(labelPanel, BorderLayout.CENTER)
            panel.add(row)
            panel.add(Box.createVerticalStrut(2))
        }

        panel.add(Box.createVerticalStrut(4))
        panel.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 1) })
        panel.add(Box.createVerticalStrut(6))

        val link = JLabel("Free trial ↗")
        link.foreground = Color(0x08, 0x7C, 0xFA)
        link.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        link.alignmentX = Component.LEFT_ALIGNMENT
        link.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                com.intellij.ide.BrowserUtil.browse("https://plugins.jetbrains.com/plugin/31217-envy/pricing")
            }
        })
        panel.add(link)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("")
            .setResizable(false)
            .setMovable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        val popupSize = panel.preferredSize
        val ownerLocation = owner.locationOnScreen
        val x = ownerLocation.x + owner.width - popupSize.width
        val y = ownerLocation.y + owner.height
        popup.show(com.intellij.ui.awt.RelativePoint(Point(x, y)))
    }

    private fun applyToggle(project: Project, id: FeatureId, enabled: Boolean) {
        when (id) {
            FeatureId.TERMINAL_CENSOR -> {
                val censor = project.getService(TerminalSecretCensor::class.java) ?: return
                if (enabled) censor.rescanAll() else censor.clearAll()
            }

            FeatureId.PRESENTATION -> {
                if (!UISettings.getInstance().presentationMode) return
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    for (fe in FileEditorManager.getInstance(project).allEditors) {
                        val vf = fe.file ?: continue
                        if (vf.fileType != DotEnvFileType) continue
                        val editor = (fe as? TextEditor)?.editor ?: continue
                        editor.foldingModel.runBatchFoldingOperation {
                            editor.foldingModel.allFoldRegions
                                .filter { it.placeholderText == "***" }
                                .forEach { it.isExpanded = !enabled }
                        }
                    }
                }
            }

            FeatureId.SYNTAX -> {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    for (fe in FileEditorManager.getInstance(project).allEditors) {
                        val vf = fe.file ?: continue
                        if (vf.fileType != DotEnvFileType) continue
                        val editor = (fe as? TextEditor)?.editor ?: continue
                        val editorEx = editor as? com.intellij.openapi.editor.ex.EditorEx ?: continue
                        editorEx.highlighter = com.intellij.openapi.editor.ex.util.LexerEditorHighlighter(
                            if (enabled) com.envy.dotenv.language.DotEnvSyntaxHighlighter()
                            else com.intellij.openapi.fileTypes.PlainSyntaxHighlighter(),
                            editor.colorsScheme
                        )
                    }
                }
            }

            FeatureId.DUPLICATE, FeatureId.SECRET_LEAK -> {
                restartInspections(project)
            }

            FeatureId.SOPS -> {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications()
                    }
                }
            }

            // autocomplete, console redaction, ghost completion, diff — checked on each invocation already
            else -> {}
        }
    }

    private fun restartInspections(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            val psiManager = com.intellij.psi.PsiManager.getInstance(project)
            for (editor in FileEditorManager.getInstance(project).allEditors) {
                val file = editor.file ?: continue
                if (file.fileType == DotEnvFileType) {
                    val psiFile = psiManager.findFile(file)
                    if (psiFile != null) {
                        try {
                            val restartWithReason = daemon.javaClass.getMethod("restart", com.intellij.psi.PsiFile::class.java, Any::class.java)
                            restartWithReason.invoke(daemon, psiFile, "Settings changed")
                        } catch (e: Exception) {
                            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                            try {
                                val restartOld = daemon.javaClass.getMethod("restart", com.intellij.psi.PsiFile::class.java)
                                restartOld.invoke(daemon, psiFile)
                            } catch (inner: Exception) {
                                if (inner is com.intellij.openapi.progress.ProcessCanceledException) throw inner
                            }
                        }
                    }
                }
            }
        }
    }

    private class ToggleSwitch(initial: Boolean) : JComponent() {
        var isOn: Boolean = initial
            set(value) { field = value; repaint() }
        var locked: Boolean = false
        var onToggle: ((Boolean) -> Unit)? = null

        private val trackW = JBUI.scale(36)
        private val trackH = JBUI.scale(18)
        private val handleSize = JBUI.scale(14)
        private val padding = JBUI.scale(2)

        init {
            preferredSize = Dimension(trackW, trackH)
            minimumSize = preferredSize
            maximumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (locked) return
                    isOn = !isOn
                    onToggle?.invoke(isOn)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val bgColor = if (locked) {
                Color(128, 128, 128, 80)
            } else if (isOn) {
                Color(0x4C, 0xAF, 0x50)
            } else {
                Color(0xC6, 0x28, 0x28)
            }
            g2.color = bgColor
            g2.fillRoundRect(0, 0, trackW, trackH, trackH, trackH)

            val handleX = if (isOn) trackW - handleSize - padding else padding
            val handleY = (trackH - handleSize) / 2
            g2.color = Color.WHITE
            g2.fillOval(handleX, handleY, handleSize, handleSize)

            g2.dispose()
        }
    }

    private class ProBadge : JLabel("PRO") {
        init {
            font = font.deriveFont(Font.BOLD, 9f)
            foreground = Color.WHITE
            isOpaque = false
            border = JBUI.Borders.empty(1, 5, 1, 5)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x08, 0x7C, 0xFA)
            g2.fillRoundRect(0, 0, width, height, 6, 6)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
