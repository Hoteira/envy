package com.envy.dotenv.sops

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile

object SopsDetector {

    private val SOPS_VALUE_PATTERN = Regex("""ENC\[AES256_GCM,""")
    private val SOPS_METADATA_PATTERN = Regex("""sops_version=|"sops"\s*:\s*\{|sops_mac=|sops_lastmodified=""")

    private val SOPS_STATE_KEY = com.intellij.openapi.util.Key.create<Boolean>("envy.sops.state")

    fun isSopsEncrypted(file: VirtualFile): Boolean {
        if (!file.isValid || file.length > 5 * 1024 * 1024) return false

        // 1. Check in-memory document (zero I/O)
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(file)
        if (doc != null) {
            val isEncrypted = isSopsEncrypted(doc.text)
            file.putUserData(SOPS_STATE_KEY, isEncrypted)
            return isEncrypted
        }

        // 2. Check cached state from a previous background read
        val cachedState = file.getUserData(SOPS_STATE_KEY)
        if (cachedState != null) return cachedState

        // 3. We are on EDT and have no cache. Return false immediately to unblock UI,
        // but kick off a background read.
        if (ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (file.isValid) {
                    try {
                        val text = file.inputStream.use { String(it.readNBytes(1024), Charsets.UTF_8) }
                        val isEncrypted = isSopsEncrypted(text)
                        file.putUserData(SOPS_STATE_KEY, isEncrypted)
                        
                        if (isEncrypted) {
                            ApplicationManager.getApplication().invokeLater {
                                val pm = com.intellij.openapi.project.ProjectManager.getInstance()
                                for (proj in pm.openProjects) {
                                    if (!proj.isDisposed) {
                                        com.intellij.ui.EditorNotifications.getInstance(proj).updateAllNotifications()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    }
                }
            }
            return false
        }

        // 4. We are already on a background thread, safe to read disk
        return try {
            file.inputStream.use { String(it.readNBytes(1024), Charsets.UTF_8) }.also {
                file.putUserData(SOPS_STATE_KEY, isSopsEncrypted(it))
            }.let { isSopsEncrypted(it) }
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            false
        }
    }

    fun isSopsEncrypted(text: String): Boolean {
        return SOPS_VALUE_PATTERN.containsMatchIn(text) || SOPS_METADATA_PATTERN.containsMatchIn(text)
    }

    fun findSopsBinary(): String? {
        val names = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("sops.exe", "sops")
        } else {
            listOf("sops")
        }
        val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator) ?: return null
        for (dir in pathDirs) {
            for (name in names) {
                val file = java.io.File(dir, name)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
        }
        return null
    }
}
