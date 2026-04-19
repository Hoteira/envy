package com.envy.dotenv.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.envy.dotenv.language.DotEnvFileType

@Service(Service.Level.PROJECT)
class EnvFileService(private val project: Project) {

    private val parseCache = java.util.concurrent.ConcurrentHashMap<VirtualFile, Pair<Long, Map<String, String>>>()

    fun findEnvFiles(): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)

        val files = FileTypeIndex.getFiles(DotEnvFileType, scope)
        
        return files.filter { file ->
            val parts = file.path.split('/')
            !parts.contains(".git") && !parts.contains("node_modules")
        }.sortedBy { it.path }
    }

    fun parseEnvFile(file: VirtualFile): Map<String, String> {
        val stamp = file.modificationStamp
        parseCache[file]?.let { (cachedStamp, result) ->
            if (cachedStamp == stamp) return result
        }
        val result = doParse(file)
        parseCache[file] = stamp to result
        return result
    }

    private fun doParse(file: VirtualFile): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val text = VfsUtilCore.loadText(file)

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Handle .envrc export lines: export KEY=value
            val effective = when {
                trimmed.startsWith("export ") -> trimmed.removePrefix("export ").trim()
                // Skip direnv commands that aren't key=value
                trimmed.startsWith("dotenv") -> continue
                trimmed.startsWith("source_env") -> continue
                trimmed.startsWith("source_up") -> continue
                trimmed.startsWith("layout ") -> continue
                trimmed.startsWith("use ") -> continue
                trimmed.startsWith("PATH_add") -> continue
                trimmed.startsWith("path_add") -> continue
                trimmed.startsWith("watch_file") -> continue
                trimmed.startsWith("log_") -> continue
                else -> trimmed
            }

            val sepIndex = effective.indexOfFirst { it == '=' || it == ':' }
            if (sepIndex <= 0) continue

            val key = effective.substring(0, sepIndex).trim()
            val value = effective.substring(sepIndex + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")

            result[key] = value
        }
        return result
    }
}