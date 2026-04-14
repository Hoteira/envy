package com.envy.dotenv.listeners

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.FileContentUtilCore

class GitignoreListener : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val gitignoreChanged = events.any { it.file?.name == ".gitignore" }
        if (!gitignoreChanged) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                for (project in ProjectManager.getInstance().openProjects) {
                    val filesToReparse = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles
                        .filter { it.name == ".env" || it.name.startsWith(".env.") }
                    
                    if (filesToReparse.isNotEmpty()) {
                        FileContentUtilCore.reparseFiles(filesToReparse)
                    }
                }
            }
        }
    }
}