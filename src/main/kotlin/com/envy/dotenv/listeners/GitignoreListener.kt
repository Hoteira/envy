package com.envy.dotenv.listeners

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.FileContentUtilCore

class GitignoreListener : AsyncFileListener {

    private val LOG = Logger.getInstance(GitignoreListener::class.java)

    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val gitignoreChanged = events.any { it.path.endsWith("/.gitignore") || it.path == ".gitignore" }
        if (!gitignoreChanged) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                try {
                    for (project in ProjectManager.getInstance().openProjects) {
                        if (project.isDisposed) continue
                        val filesToReparse = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles
                            .filter { it.name == ".env" || it.name.startsWith(".env.") }

                        if (filesToReparse.isNotEmpty()) {
                            FileContentUtilCore.reparseFiles(filesToReparse)
                        }
                    }
                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    LOG.warn("Failed to reparse .env files after .gitignore change", e)
                }
            }
        }
    }
}
