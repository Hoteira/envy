package com.envy.dotenv.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.envy.dotenv.language.psi.DotEnvFile

class DuplicateKeyInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Duplicate key in .env file"
    override fun getGroupDisplayName(): String = "DotEnv"
    override fun getShortName(): String = "DotEnvDuplicateKey"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DotEnvFile) return

                val text = file.text
                val lines = text.lines()

                // Map of key name -> first line number (1-indexed)
                val seen = mutableMapOf<String, Int>()

                for ((index, line) in lines.withIndex()) {
                    val trimmed = line.trim()

                    // Skip comments and blank lines
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                    // Strip optional "export " prefix
                    val effective = if (trimmed.startsWith("export ")) {
                        trimmed.removePrefix("export ").trim()
                    } else {
                        trimmed
                    }

                    // Extract key (everything before = or :)
                    val sepIndex = effective.indexOfFirst { it == '=' || it == ':' }
                    if (sepIndex <= 0) continue

                    val key = effective.substring(0, sepIndex).trim()
                    val lineNum = index + 1

                    if (seen.containsKey(key)) {
                        // Find the PSI element at this offset to attach the warning
                        val lineStartOffset = text.lines().take(index).sumOf { it.length + 1 }
                        val keyOffset = lineStartOffset + line.indexOf(key)
                        val element = file.findElementAt(keyOffset)

                        if (element != null) {
                            val lineStartOffset = text.lines().take(index).sumOf { it.length + 1 }
                            val lineEndOffset = lineStartOffset + line.length

                            val range = com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset)

                            val lineElement = file.findElementAt(lineStartOffset)?.parent ?: file

                            holder.registerProblem(
                                lineElement,
                                "Duplicate key '$key' — first defined on line ${seen[key]}",
                                com.intellij.codeInspection.ProblemHighlightType.WARNING,
                                range.shiftLeft(lineElement.textRange.startOffset)
                            )
                        }
                    } else {
                        seen[key] = lineNum
                    }
                }
            }
        }
    }
}