package com.envy.dotenv.sops

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.envy.dotenv.language.DotEnvFileType
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds decrypted SOPS sessions and writes edits back as ciphertext.
 *
 * Save rules:
 *  - Re-encrypt only when the decrypted text changed since it was last written (modification
 *    stamp). Save All fires on focus loss, builds and autosave; re-encrypting unchanged text would
 *    rewrite the file with new ciphertext and MAC every time and churn it in git.
 *  - Save All encrypts in the background. Closing the editor, the project or the IDE flushes
 *    synchronously, so edits are never dropped by a pending background write at shutdown.
 *  - The ciphertext is written straight to disk (then VFS refresh), so a write never waits on the
 *    EDT and can't deadlock with a blocking flush.
 *  - Before writing, the new file's SOPS metadata is compared with the old one. If recipients,
 *    key groups, thresholds or encryption rules would change, nothing is written.
 */
@Service(Service.Level.PROJECT)
class SopsService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(SopsService::class.java)

    class SopsSession(
        val realFile: VirtualFile,
        val decryptedFile: LightVirtualFile,
        val sopsPath: String,
        val document: Document,
    ) {
        /** Modification stamp of [document] when its text last matched the ciphertext on disk. */
        @Volatile var savedStamp: Long = document.modificationStamp
        /** Stamp a queued background save is about to write, so Save All doesn't queue duplicates. */
        @Volatile var inFlightStamp: Long = -1L
        /** Serialises encrypt-and-write so a background save and a blocking flush never overlap. */
        val writeLock = Any()
    }

    private val sessions = ConcurrentHashMap<LightVirtualFile, SopsSession>()
    private val fileToSession = ConcurrentHashMap<VirtualFile, SopsSession>()

    init {
        val busConnection = ApplicationManager.getApplication().messageBus.connect(this)

        busConnection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
            override fun before(events: MutableList<out com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                for (event in events) {
                    if (event is com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent) {
                        closeSession(event.file)
                    }
                }
            }
        })

        busConnection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeAllDocumentsSaving() {
                for (session in sessions.values) {
                    if (isDirty(session)) saveInBackground(session)
                }
            }
        })
    }

    fun getSession(realFile: VirtualFile): SopsSession? = fileToSession[realFile]
    fun getSessionForDecrypted(lightFile: LightVirtualFile): SopsSession? = sessions[lightFile]

    fun isDirty(session: SopsSession): Boolean = session.document.modificationStamp != session.savedStamp

    @Synchronized
    fun decrypt(realFile: VirtualFile): SopsSession? {
        val existing = fileToSession[realFile]
        if (existing != null) return existing

        val sopsPath = SopsDetector.findSopsBinary()
        if (sopsPath == null) {
            LOG.warn("sops binary not found")
            return null
        }

        val result = SopsCli.decrypt(sopsPath, realFile.path)
        if (!result.success) {
            LOG.warn("sops decrypt failed for ${realFile.path}: ${result.error}")
            return null
        }

        val lightFile = LightVirtualFile("${realFile.name}.decrypted", DotEnvFileType, result.output)
        lightFile.isWritable = true

        // Keep our own reference to the document: saving must not depend on FileDocumentManager
        // still caching a document for a light file.
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(lightFile)
        } ?: EditorFactory.getInstance().createDocument(result.output)

        val session = SopsSession(realFile, lightFile, sopsPath, document)
        sessions[lightFile] = session
        fileToSession[realFile] = session
        return session
    }

    /** Save All path: encrypt off the EDT; failures show an error and keep the session dirty. */
    private fun saveInBackground(session: SopsSession) {
        val stamp = session.document.modificationStamp
        if (stamp == session.inFlightStamp) return
        session.inFlightStamp = stamp
        val plaintext = ReadAction.compute<String, RuntimeException> { session.document.text }

        object : Task.Backgroundable(project, "Encrypting ${session.realFile.name} with SOPS", false) {
            override fun run(indicator: ProgressIndicator) {
                val error = encryptAndWrite(session, plaintext, stamp)
                if (error != null) reportFailure(session, error, closing = false)
            }

            override fun onFinished() {
                if (session.inFlightStamp == stamp) session.inFlightStamp = -1L
            }
        }.queue()
    }

    /**
     * Close path (editor tab, project or IDE): write pending edits before the decrypted document goes
     * away. Runs under a modal progress when it can, otherwise directly on the calling thread.
     */
    fun flushBlocking(session: SopsSession) {
        if (!isDirty(session)) return
        val stamp = session.document.modificationStamp
        val plaintext = ReadAction.compute<String, RuntimeException> { session.document.text }

        val app = ApplicationManager.getApplication()
        val error = if (app.isDispatchThread && !app.isWriteAccessAllowed && !project.isDisposed) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String?, RuntimeException> { encryptAndWrite(session, plaintext, stamp) },
                "Encrypting ${session.realFile.name} with SOPS",
                false,
                project
            )
        } else {
            encryptAndWrite(session, plaintext, stamp)
        }
        if (error != null) reportFailure(session, error, closing = true)
    }

    /** Returns null on success, or a message describing why nothing was written. */
    private fun encryptAndWrite(session: SopsSession, plaintext: String, stamp: Long): String? {
        synchronized(session.writeLock) {
            if (session.savedStamp == stamp) return null // another path already wrote this text
            if (!session.realFile.isValid) return "${session.realFile.name} no longer exists."

            val path = try {
                session.realFile.toNioPath()
            } catch (e: UnsupportedOperationException) {
                return "${session.realFile.name} is not a local file."
            }

            val currentCiphertext = try {
                Files.readString(path)
            } catch (e: Exception) {
                return "Couldn't read ${session.realFile.name}: ${e.message}"
            }

            val args = SopsCli.extractEncryptArgs(currentCiphertext)
            val result = SopsCli.encrypt(session.sopsPath, session.realFile.path, plaintext, args)
            if (!result.success) return result.error.ifBlank { "sops exited with an error." }

            val drift = SopsCli.securityConfigDiff(currentCiphertext, result.output)
            if (drift.isNotEmpty()) {
                LOG.warn("Refusing to write ${session.realFile.path}: SOPS settings would change: $drift")
                return "Re-encrypting would change this file's SOPS settings, so nothing was written:\n" +
                    drift.joinToString("\n") { "  $it" } +
                    "\n\nYour edits are still in the decrypted editor. Use `sops edit` for this file for now."
            }

            try {
                Files.writeString(path, result.output)
            } catch (e: Exception) {
                return "Couldn't write ${session.realFile.name}: ${e.message}"
            }
            session.savedStamp = stamp
        }
        VfsUtil.markDirtyAndRefresh(true, false, false, session.realFile)
        return null
    }

    private fun reportFailure(session: SopsSession, error: String, closing: Boolean) {
        LOG.warn("sops re-encrypt failed for ${session.realFile.path}: $error")
        if (closing) {
            // No modal dialogs while editors or the IDE are closing.
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance().getNotificationGroup("EnvY")
                    .createNotification(
                        "Changes to ${session.realFile.name} were not saved",
                        error,
                        NotificationType.ERROR
                    )
                    .notify(if (project.isDisposed) null else project)
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Failed to re-encrypt ${session.realFile.name}:\n$error",
                    "SOPS Encryption Error"
                )
            }
        }
    }

    fun closeSession(realFile: VirtualFile) {
        val session = fileToSession.remove(realFile) ?: return
        sessions.remove(session.decryptedFile)
    }

    override fun dispose() {
        // Safety net: editors normally flush on close, but never drop edits on project close.
        for (session in sessions.values) {
            try {
                if (isDirty(session)) {
                    val stamp = session.document.modificationStamp
                    val plaintext = ReadAction.compute<String, RuntimeException> { session.document.text }
                    encryptAndWrite(session, plaintext, stamp)?.let { LOG.warn("SOPS flush on dispose failed: $it") }
                }
            } catch (e: Exception) {
                LOG.warn("SOPS flush on dispose failed", e)
            }
        }
        sessions.clear()
        fileToSession.clear()
    }
}
