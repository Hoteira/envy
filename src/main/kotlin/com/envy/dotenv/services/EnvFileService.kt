package com.envy.dotenv.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.envy.dotenv.language.DotEnvFileType
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class EnvFileService(private val project: Project) : Disposable {

    private val fileKeyValues = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile private var fileListCache: List<VirtualFile>? = null
    @Volatile private var allKeysCache: Set<String>? = null
    @Volatile private var allKeysSortedCache: List<String>? = null
    @Volatile private var allKeyValuesCache: Map<String, String>? = null
    @Volatile private var disposed = false

    private val connection = project.messageBus.connect(this)

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (disposed) return
                var needsFileListUpdate = false
                val updatedFiles = mutableListOf<VirtualFile>()

                for (event in events) {
                    val path = event.path
                    if (path.endsWith(".env") || path.contains("/.env.")) {
                        when (event) {
                            is VFileCreateEvent,
                            is VFileDeleteEvent,
                            is VFilePropertyChangeEvent -> {
                                needsFileListUpdate = true
                                fileKeyValues.remove(path)
                            }
                            is VFileContentChangeEvent -> {
                                updatedFiles.add(event.file)
                            }
                        }
                    }
                }

                if (needsFileListUpdate) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (disposed) return@executeOnPooledThread
                        val files = ApplicationManager.getApplication().runReadAction(Computable {
                            if (project.isDisposed) return@Computable emptyList()
                            FileTypeIndex.getFiles(DotEnvFileType, GlobalSearchScope.projectScope(project)).filter { file ->
                                val p = file.path
                                !p.contains("/.git/") && !p.contains("/node_modules/")
                            }.sortedBy { it.path }
                        })
                        fileListCache = files
                        rebuildGlobalCaches()
                    }
                } else if (updatedFiles.isNotEmpty()) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (disposed) return@executeOnPooledThread
                        for (file in updatedFiles) {
                            parseAndStore(file)
                        }
                        rebuildGlobalCaches()
                    }
                }
            }
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed) return
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val path = file.path
                if (path.endsWith(".env") || path.contains("/.env.")) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (disposed) return@executeOnPooledThread
                        parseAndStore(file, event.document)
                        rebuildGlobalCaches()
                    }
                }
            }
        }, this)

        // Initial build
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val files = ApplicationManager.getApplication().runReadAction(Computable {
                if (project.isDisposed) return@Computable emptyList()
                FileTypeIndex.getFiles(DotEnvFileType, GlobalSearchScope.projectScope(project)).filter { file ->
                    val p = file.path
                    !p.contains("/.git/") && !p.contains("/node_modules/")
                }.sortedBy { it.path }
            })
            fileListCache = files
            for (file in files) {
                parseAndStore(file)
            }
            rebuildGlobalCaches()
        }
    }

    private fun parseAndStore(file: VirtualFile, document: com.intellij.openapi.editor.Document? = null) {
        if (disposed || !file.isValid) {
            fileKeyValues.remove(file.path)
            return
        }
        val text = ApplicationManager.getApplication().runReadAction(Computable<CharSequence?> {
            if (!file.isValid) return@Computable null
            val doc = document ?: FileDocumentManager.getInstance().getCachedDocument(file)
            doc?.immutableCharSequence ?: VfsUtilCore.loadText(file)
        })
        if (text == null) {
            fileKeyValues.remove(file.path)
            return
        }
        val parsed = EnvParser.parse(text)
        val map = parsed.entries.associate { it.key to it.value }
        fileKeyValues[file.path] = map
    }

    private fun rebuildGlobalCaches() {
        if (disposed) return
        val mergedValues = mutableMapOf<String, String>()
        val allKeys = mutableSetOf<String>()
        val files = fileListCache ?: emptyList()

        for (file in files) {
            val map = fileKeyValues[file.path] ?: continue
            for ((key, value) in map) {
                mergedValues.putIfAbsent(key, value)
                allKeys.add(key)
            }
        }

        allKeyValuesCache = mergedValues
        allKeysCache = allKeys
        allKeysSortedCache = allKeys.sortedWith(compareBy({ it.length }, { it }))
    }

    fun findEnvFiles(): List<VirtualFile> {
        return fileListCache ?: emptyList()
    }

    fun parseEnvFile(file: VirtualFile): Map<String, String> {
        val existing = fileKeyValues[file.path]
        if (existing != null) return existing

        if (!file.isValid) return emptyMap()
        val text = ApplicationManager.getApplication().runReadAction(Computable<CharSequence?> {
            if (!file.isValid) return@Computable null
            val doc = FileDocumentManager.getInstance().getCachedDocument(file)
            doc?.immutableCharSequence ?: VfsUtilCore.loadText(file)
        }) ?: return emptyMap()

        val parsed = EnvParser.parse(text)
        val map = parsed.entries.associate { it.key to it.value }
        fileKeyValues[file.path] = map
        return map
    }

    fun getAllKeys(): Set<String> {
        return allKeysCache ?: emptySet()
    }

    fun getAllKeysSorted(): List<String> {
        return allKeysSortedCache ?: emptyList()
    }

    fun getAllKeyValues(): Map<String, String> {
        return allKeyValuesCache ?: emptyMap()
    }

    override fun dispose() {
        disposed = true
        fileKeyValues.clear()
        fileListCache = null
        allKeysCache = null
        allKeysSortedCache = null
        allKeyValuesCache = null
    }
}
