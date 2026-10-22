package com.envy.dotenv.listeners

import com.envy.dotenv.services.EnvFileNames
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The secret-leak inspection says whether a .env file is tracked, untracked or ignored. Git status
 * arrives after the file is first highlighted (and changes after `git rm --cached` or a .gitignore
 * edit), so re-run the inspection on open .env files whenever their status actually changes.
 * Registered under <projectListeners> in plugin.xml.
 */
class EnvVcsStatusListener(private val project: Project) : ChangeListListener {

    private val lastStatus = ConcurrentHashMap<String, String>()

    override fun changeListUpdateDone() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val statusManager = FileStatusManager.getInstance(project)
            val changes = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            for (file in FileEditorManager.getInstance(project).openFiles) {
                if (!EnvFileNames.isEnvFileName(file.name)) continue
                val status = when {
                    changes.isIgnoredFile(file) -> FileStatus.IGNORED.id
                    changes.isUnversioned(file) -> FileStatus.UNKNOWN.id
                    else -> statusManager.getStatus(file).id
                }
                if (lastStatus.put(file.path, status) == status) continue
                psiManager.findFile(file)?.let { restartHighlighting(it) }
            }
        }
    }

    private fun restartHighlighting(file: PsiFile) {
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        try {
            daemon.javaClass.getMethod("restart", PsiFile::class.java, Any::class.java)
                .invoke(daemon, file, "EnvY: git status of a .env file changed")
        } catch (_: NoSuchMethodException) {
            try {
                daemon.javaClass.getMethod("restart", PsiFile::class.java).invoke(daemon, file)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }
}
