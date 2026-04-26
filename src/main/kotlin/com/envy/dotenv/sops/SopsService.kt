package com.envy.dotenv.sops

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.envy.dotenv.language.DotEnvFileType
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SopsService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(SopsService::class.java)

    data class SopsSession(
        val realFile: VirtualFile,
        val decryptedFile: LightVirtualFile,
        val sopsPath: String
    )

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
                    for ((_, session) in sessions) {
                        val doc = FileDocumentManager.getInstance().getCachedDocument(session.decryptedFile) ?: continue
                        if (!doc.isWritable) continue
                        val plaintext = doc.text
                        encryptAndSave(session, plaintext)
                    }
                }
            })
    }

    fun getSession(realFile: VirtualFile): SopsSession? = fileToSession[realFile]
    fun getSessionForDecrypted(lightFile: LightVirtualFile): SopsSession? = sessions[lightFile]

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

        val session = SopsSession(realFile, lightFile, sopsPath)
        sessions[lightFile] = session
        fileToSession[realFile] = session
        return session
    }

    private fun encryptAndSave(session: SopsSession, plaintext: String) {
        val realFileContent = FileDocumentManager.getInstance().getDocument(session.realFile)?.text 
            ?: String(session.realFile.contentsToByteArray(), Charsets.UTF_8)
            
        ApplicationManager.getApplication().executeOnPooledThread {
            val keyArgs = SopsCli.extractKeyArgs(realFileContent)
            val result = SopsCli.encrypt(session.sopsPath, session.realFile.path, plaintext, keyArgs)
            if (result.success) {
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().runWriteAction {
                        if (session.realFile.isValid) {
                            session.realFile.setBinaryContent(result.output.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            } else {
                LOG.warn("sops re-encrypt failed for ${session.realFile.path}: ${result.error}")
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        "Failed to re-encrypt ${session.realFile.name}:\n${result.error}",
                        "SOPS Encryption Error"
                    )
                }
            }
        }
    }

    fun closeSession(realFile: VirtualFile) {
        val session = fileToSession.remove(realFile) ?: return
        sessions.remove(session.decryptedFile)
    }

    override fun dispose() {
        sessions.clear()
        fileToSession.clear()
    }
}
