package com.envy.dotenv.listeners

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.ProjectManager
import com.envy.dotenv.language.DotEnvFileType
import com.envy.dotenv.services.PresentationModeState

class DotEnvPresentationModeListener : UISettingsListener {

    override fun uiSettingsChanged(uiSettings: UISettings) {
        val inPresentationMode = uiSettings.presentationMode
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val state = project.getService(PresentationModeState::class.java) ?: continue
                for (fileEditor in FileEditorManager.getInstance(project).allEditors) {
                    val vFile = fileEditor.file ?: continue
                    if (vFile.fileType != DotEnvFileType) continue
                    val editor = (fileEditor as? TextEditor)?.editor ?: continue
                    editor.foldingModel.runBatchFoldingOperation {
                        editor.foldingModel.allFoldRegions
                            .filter { it.placeholderText == "***" }
                            .forEach { region ->
                                if (inPresentationMode) {
                                    // Only collapse if user hasn't manually revealed this region
                                    if (!state.isRevealed(vFile, region.startOffset)) {
                                        region.isExpanded = false
                                    }
                                } else {
                                    region.isExpanded = true
                                    // Clear reveals when leaving presentation mode
                                }
                            }
                    }
                    if (!inPresentationMode) {
                        state.clearRevealed(vFile)
                    }
                }
            }
        }
    }
}
