package com.envy.dotenv.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.guessProjectDir


@Service(Service.Level.PROJECT)
class EnvFileService(private val project: Project) {

    fun findEnvFiles(): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        val result = mutableListOf<VirtualFile>()
        collectEnvFiles(baseDir, result)
        return result.sortedBy { it.path }
    }

    private fun collectEnvFiles(dir: VirtualFile, result: MutableList<VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name in listOf("node_modules", ".git", "build", "target", ".gradle", ".idea", ".intellijPlatform", "dist", "out", "vendor")) continue
                collectEnvFiles(child, result)
            } else if (child.name == ".env" || child.name.startsWith(".env.") || child.name == ".envrc") {
                result.add(child)
            }
        }
    }

    fun parseEnvFile(file: VirtualFile): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val text = String(file.contentsToByteArray(), Charsets.UTF_8)

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