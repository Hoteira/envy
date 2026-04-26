package com.envy.dotenv.sops

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.envy.dotenv.language.DotEnvFileType
import java.util.function.Function
import javax.swing.JComponent

class SopsEditorNotification : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (file.fileType != DotEnvFileType) return null
        if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.sopsIntegration) return null
        if (!com.envy.dotenv.licensing.LicenseChecker.isPaidFeatureAvailableStrict()) return null
        if (!SopsDetector.isSopsEncrypted(file)) return null

        return Function { editor ->
            if (editor is SopsSplitEditor) return@Function null

            val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            panel.icon(AllIcons.Nodes.SecurityRole)
            panel.text = "SOPS Encrypted File Detected"

            val sopsPath = SopsDetector.findSopsBinary()
            if (sopsPath != null) {
                val svc = project.getService(SopsService::class.java) ?: return@Function panel
                val existing = svc.getSession(file)
                if (existing != null) {
                    panel.createActionLabel("Open Decrypted View") {
                        FileEditorManager.getInstance(project).setSelectedEditor(file, "envy-sops-split")
                    }
                } else {
                    panel.createActionLabel("Decrypt & Edit") {
                        FileEditorManager.getInstance(project).setSelectedEditor(file, "envy-sops-split")
                    }
                }
            } else {
                panel.createActionLabel("Install sops") {
                    com.intellij.ide.BrowserUtil.browse("https://github.com/getsops/sops#install")
                }
                panel.text = "SOPS Encrypted File Detected — sops CLI not found"
            }

            panel
        }
    }
}
