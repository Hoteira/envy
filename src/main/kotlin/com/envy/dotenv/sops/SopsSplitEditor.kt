package com.envy.dotenv.sops

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.envy.dotenv.language.DotEnvFileType
import java.beans.PropertyChangeListener
import javax.swing.*

class SopsSplitEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.fileType != DotEnvFileType) return false
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.sopsIntegration) return false
        if (!com.envy.dotenv.licensing.LicenseChecker.isPaidFeatureAvailable()) return false
        return SopsDetector.isSopsEncrypted(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return SopsSplitEditor(project, file)
    }

    override fun getEditorTypeId(): String = "envy-sops-split"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

class SopsSplitEditor(
    private val project: Project,
    private val realFile: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val splitter = JBSplitter(false, 0.5f)
    private val wrapper = JPanel(java.awt.BorderLayout())

    private var rawEditor: com.intellij.openapi.editor.Editor? = null
    private var decryptedEditor: com.intellij.openapi.editor.Editor? = null
    private var decrypted = false

    init {
        val sopsPath = SopsDetector.findSopsBinary()
        if (sopsPath == null) {
            val infoPanel = JPanel(java.awt.BorderLayout())
            infoPanel.border = JBUI.Borders.empty(16)
            val label = JLabel("sops CLI not found. Install it to use the decrypted editor.")
            label.horizontalAlignment = SwingConstants.CENTER
            infoPanel.add(label, java.awt.BorderLayout.CENTER)
            wrapper.add(infoPanel, java.awt.BorderLayout.CENTER)
        } else {
            val loadingPanel = JPanel(java.awt.BorderLayout())
            val label = JLabel("Decrypting SOPS file...", SwingConstants.CENTER)
            loadingPanel.add(label, java.awt.BorderLayout.CENTER)
            wrapper.add(loadingPanel, java.awt.BorderLayout.CENTER)

            ApplicationManager.getApplication().executeOnPooledThread {
                performDecrypt()
            }
        }
    }

    private fun performDecrypt() {
        val svc = project.getService(SopsService::class.java) ?: return
        val session = svc.decrypt(realFile)

        ApplicationManager.getApplication().invokeLater {
            if (session == null) {
                wrapper.removeAll()
                val errorPanel = JPanel(java.awt.BorderLayout())
                val label = JLabel("Failed to decrypt ${realFile.name}. Check that you have the correct keys configured.", SwingConstants.CENTER)
                errorPanel.add(label, java.awt.BorderLayout.CENTER)
                wrapper.add(errorPanel, java.awt.BorderLayout.CENTER)
                wrapper.revalidate()
                wrapper.repaint()
                return@invokeLater
            }

            // Release any previously created editors
            rawEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
            decryptedEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
            rawEditor = null
            decryptedEditor = null

            wrapper.removeAll()

            val factory = EditorFactory.getInstance()

            // Left: raw ciphertext (read-only)
            val rawDoc = FileDocumentManager.getInstance().getDocument(realFile)
            rawEditor = if (rawDoc != null) {
                factory.createEditor(rawDoc, project, realFile, true)
            } else {
                val doc = factory.createDocument(String(realFile.contentsToByteArray(), Charsets.UTF_8))
                doc.setReadOnly(true)
                factory.createViewer(doc, project)
            }.also { editor ->
                (editor as? EditorEx)?.let { ex ->
                    ex.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, DotEnvFileType)
                }
            }

            // Right: decrypted plaintext (editable)
            val decDoc = FileDocumentManager.getInstance().getDocument(session.decryptedFile)
                ?: factory.createDocument(session.decryptedFile.content.toString())
            
            decryptedEditor = if (FileDocumentManager.getInstance().getFile(decDoc) == session.decryptedFile) {
                factory.createEditor(decDoc, project, session.decryptedFile, false)
            } else {
                factory.createEditor(decDoc, project)
            }.also { editor ->
                (editor as? EditorEx)?.let { ex ->
                    ex.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, DotEnvFileType)
                }
            }

            val leftPanel = JPanel(java.awt.BorderLayout())
            leftPanel.add(JLabel("  Encrypted (read-only)").also { it.border = JBUI.Borders.emptyBottom(4) }, java.awt.BorderLayout.NORTH)
            leftPanel.add(rawEditor!!.component, java.awt.BorderLayout.CENTER)

            val rightPanel = JPanel(java.awt.BorderLayout())
            rightPanel.add(JLabel("  Decrypted (editable) — saves re-encrypt automatically").also { it.border = JBUI.Borders.emptyBottom(4) }, java.awt.BorderLayout.NORTH)
            rightPanel.add(decryptedEditor!!.component, java.awt.BorderLayout.CENTER)

            splitter.firstComponent = leftPanel
            splitter.secondComponent = rightPanel

            wrapper.add(splitter, java.awt.BorderLayout.CENTER)
            wrapper.revalidate()
            wrapper.repaint()
            decrypted = true
        }
    }

    override fun getComponent(): JComponent = wrapper
    override fun getPreferredFocusedComponent(): JComponent? = decryptedEditor?.contentComponent
    override fun getName(): String = "SOPS Decrypted"
    override fun isModified(): Boolean {
        val doc = decryptedEditor?.document ?: return false
        return FileDocumentManager.getInstance().isDocumentUnsaved(doc)
    }
    override fun isValid(): Boolean = realFile.isValid

    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = realFile

    override fun dispose() {
        rawEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        decryptedEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        project.getService(SopsService::class.java)?.closeSession(realFile)
    }
}
