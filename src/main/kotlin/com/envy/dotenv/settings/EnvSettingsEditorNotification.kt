package com.envy.dotenv.settings

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import com.envy.dotenv.language.DotEnvFileType
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class EnvSettingsEditorNotification : EditorNotificationProvider, DumbAware {

    companion object {
        private val ICON = IconLoader.getIcon("/icons/envySettings.svg", EnvSettingsEditorNotification::class.java)
    }

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (file.fileType != DotEnvFileType) return null

        return Function { _ ->
            val icon = JLabel(ICON)
            icon.toolTipText = "EnvY Settings"
            icon.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            icon.border = JBUI.Borders.empty(2, 4)
            icon.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    EnvSettingsPopup.show(project, icon)
                }
            })

            val panel = JPanel(BorderLayout())
            panel.isOpaque = false
            panel.preferredSize = Dimension(0, JBUI.scale(24))
            panel.add(icon, BorderLayout.EAST)
            panel
        }
    }
}
