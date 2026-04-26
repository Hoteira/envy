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
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.CachedValue
import com.intellij.util.Alarm
import com.envy.dotenv.language.DotEnvFileType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.intellij.util.messages.Topic

interface EnvFileListener {
    companion object {
        val TOPIC = Topic.create("EnvFile changes", EnvFileListener::class.java)
    }
    fun envFilesChanged()
}

@Service(Service.Level.PROJECT)
class EnvFileService(private val project: Project) : Disposable {

    private companion object {
        const val MAX_ENV_FILE_SIZE = 5L * 1024 * 1024 // 5 MB
    }

    private val fileKeyValues = ConcurrentHashMap<String, Map<String, String>>()
    private val modificationCount = AtomicLong()
    private val modificationTracker = ModificationTracker { modificationCount.get() }
    private val pendingFiles = ConcurrentHashMap.newKeySet<VirtualFile>()
    private val debounceAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile private var disposed = false

    private fun isEnvPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == ".env" || name == ".envrc" || (name.startsWith(".env.") && !name.contains('/'))
    }

    private fun notifyListeners() {
        modificationCount.incrementAndGet()
        ApplicationManager.getApplication().messageBus.syncPublisher(EnvFileListener.TOPIC).envFilesChanged()
    }

    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    private val fileListCachedValue: CachedValue<List<VirtualFile>> =
        CachedValuesManager.getManager(project).createCachedValue {
            val files = ApplicationManager.getApplication().runReadAction(Computable {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                if (project.isDisposed) return@Computable emptyList<VirtualFile>()
                FileTypeIndex.getFiles(DotEnvFileType, GlobalSearchScope.projectScope(project)).filter { file ->
                    val p = file.path
                    !p.contains("/.git/") && !p.contains("/node_modules/")
                }.sortedBy { it.path }
            })
            CachedValueProvider.Result.create(files, modificationTracker)
        }

    private val allKeyValuesCachedValue: CachedValue<Map<String, String>> =
        CachedValuesManager.getManager(project).createCachedValue {
            val mergedValues = mutableMapOf<String, String>()
            for (file in fileListCachedValue.value) {
                val map = fileKeyValues[file.path] ?: continue
                for ((key, value) in map) {
                    mergedValues.putIfAbsent(key, value)
                }
            }
            CachedValueProvider.Result.create<Map<String, String>>(mergedValues, modificationTracker)
        }

    private val allKeysCachedValue: CachedValue<Set<String>> =
        CachedValuesManager.getManager(project).createCachedValue {
            CachedValueProvider.Result.create<Set<String>>(
                allKeyValuesCachedValue.value.keys.toSet(),
                modificationTracker
            )
        }

    private val allKeysSortedCachedValue: CachedValue<List<String>> =
        CachedValuesManager.getManager(project).createCachedValue {
            CachedValueProvider.Result.create(
                allKeysCachedValue.value.sortedWith(compareBy({ it.length }, { it })),
                modificationTracker
            )
        }

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (disposed) return
                var needsFileListUpdate = false
                val updatedFiles = mutableListOf<VirtualFile>()

                for (event in events) {
                    val path = event.path
                    if (isEnvPath(path)) {
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
                        notifyListeners()
                        for (file in fileListCachedValue.value) {
                            if (!fileKeyValues.containsKey(file.path)) {
                                parseAndStore(file)
                            }
                        }
                        notifyListeners()
                    }
                } else if (updatedFiles.isNotEmpty()) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (disposed) return@executeOnPooledThread
                        for (file in updatedFiles) {
                            parseAndStore(file)
                        }
                        notifyListeners()
                    }
                }
            }
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed) return
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val path = file.path
                if (isEnvPath(path)) {
                    pendingFiles.add(file)
                    debounceAlarm.cancelAllRequests()
                    debounceAlarm.addRequest({ processPendingFiles() }, 300)
                }
            }
        }, this)

        // Initial build
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            for (file in fileListCachedValue.value) {
                parseAndStore(file)
            }
            notifyListeners()
        }
    }

    private fun processPendingFiles() {
        if (disposed) return
        val files = pendingFiles.toList()
        pendingFiles.clear()
        for (file in files) {
            parseAndStore(file)
        }
        notifyListeners()
    }

    private fun parseAndStore(file: VirtualFile, document: com.intellij.openapi.editor.Document? = null) {
        if (disposed || !file.isValid) {
            fileKeyValues.remove(file.path)
            return
        }
        val text = ApplicationManager.getApplication().runReadAction(Computable<CharSequence?> {
            if (!file.isValid) return@Computable null
            if (file.length > MAX_ENV_FILE_SIZE) return@Computable null
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

    fun findEnvFiles(): List<VirtualFile> = fileListCachedValue.value

    fun parseEnvFile(file: VirtualFile): Map<String, String> {
        val existing = fileKeyValues[file.path]
        if (existing != null) return existing

        if (!file.isValid || file.length > MAX_ENV_FILE_SIZE) return emptyMap()
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

    fun getAllKeys(): Set<String> = allKeysCachedValue.value

    fun getAllKeysSorted(): List<String> = allKeysSortedCachedValue.value

    fun getAllKeyValues(): Map<String, String> = allKeyValuesCachedValue.value

    fun getAllParsedEntries(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (file in fileListCachedValue.value) {
            val map = fileKeyValues[file.path] ?: continue
            for ((key, value) in map) {
                result.add(key to value)
            }
        }
        return result
    }

    override fun dispose() {
        disposed = true
        fileKeyValues.clear()
        pendingFiles.clear()
    }
}
