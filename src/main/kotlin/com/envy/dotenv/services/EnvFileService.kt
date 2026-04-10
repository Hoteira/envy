package com.envy.dotenv.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.guessProjectDir


@Service(Service.Level.PROJECT)
class EnvFileService(private val project: Project) {

    fun findEnvFiles(): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()

        return baseDir.children.filter { file ->
            !file.isDirectory && (
                    file.name == ".env" ||
                            file.name.startsWith(".env.")
                    )
        }.sortedBy { it.name }
    }

    fun parseEnvFile(file: VirtualFile): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val text = String(file.contentsToByteArray(), Charsets.UTF_8)

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val effective = if (trimmed.startsWith("export ")) {
                trimmed.removePrefix("export ").trim()
            } else {
                trimmed
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