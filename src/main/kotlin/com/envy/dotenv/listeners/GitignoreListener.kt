package com.envy.dotenv.listeners

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager

class GitignoreListener : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val gitignoreChanged = events.any { it.file?.name == ".gitignore" }
        if (!gitignoreChanged) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                for (project in ProjectManager.getInstance().openProjects) {
                    val analyzer = DaemonCodeAnalyzer.getInstance(project)
                    val psiManager = PsiManager.getInstance(project)

                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles
                        .filter { it.name == ".env" || it.name.startsWith(".env.") }
                        .forEach { file ->
                            val psiFile = psiManager.findFile(file) ?: return@forEach
                            analyzer.restart(psiFile)
                        }
                }
            }
        }
    }
}