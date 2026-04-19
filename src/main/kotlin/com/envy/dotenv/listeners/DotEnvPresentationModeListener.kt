package com.envy.dotenv.listeners

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.ProjectManager
import com.envy.dotenv.language.DotEnvFileType

class DotEnvPresentationModeListener : UISettingsListener {

    override fun uiSettingsChanged(uiSettings: UISettings) {
        val inPresentationMode = uiSettings.presentationMode
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                for (fileEditor in FileEditorManager.getInstance(project).allEditors) {
                    if (fileEditor.file?.fileType != DotEnvFileType) continue
                    val editor = (fileEditor as? TextEditor)?.editor ?: continue
                    editor.foldingModel.runBatchFoldingOperation {
                        editor.foldingModel.allFoldRegions
                            .filter { it.placeholderText == "***" }
                            .forEach { it.isExpanded = !inPresentationMode }
                    }
                }
            }
        }
    }
}
