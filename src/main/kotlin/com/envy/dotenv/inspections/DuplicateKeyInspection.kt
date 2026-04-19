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

                // Build accurate line start offsets — handles LF, CR, and CRLF
                val lineStartOffsets = IntArray(lines.size)
                var pos = 0
                for (i in lines.indices) {
                    lineStartOffsets[i] = pos
                    pos += lines[i].length
                    if (pos < text.length) {
                        if (text[pos] == '\r') pos++
                        if (pos < text.length && text[pos] == '\n') pos++
                    }
                }

                // Map of key name -> first line number (1-indexed)
                val seen = mutableMapOf<String, Int>()

                for ((index, line) in lines.withIndex()) {
                    val lineStartOffset = lineStartOffsets[index]
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
                        val keyOffset = lineStartOffset + line.indexOf(key)
                        val element = file.findElementAt(keyOffset) ?: continue

                        holder.registerProblem(
                            element,
                            "Duplicate key '$key' - first defined on line ${seen[key]}",
                            com.intellij.codeInspection.ProblemHighlightType.WARNING,
                            RemoveDuplicateFix(key, index)
                        )
                    } else {
                        seen[key] = lineNum
                    }
                }
            }
        }
    }
}

class RemoveDuplicateFix(private val key: String, private val lineIndex: Int) : com.intellij.codeInspection.LocalQuickFix {
    override fun getName(): String = "Remove duplicate '$key'"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        if (lineIndex < 0 || lineIndex >= document.lineCount) return

        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val deleteEnd = if (lineEnd < document.textLength && document.text[lineEnd] == '\n') lineEnd + 1 else lineEnd

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(lineStart, deleteEnd)
        }
    }
}