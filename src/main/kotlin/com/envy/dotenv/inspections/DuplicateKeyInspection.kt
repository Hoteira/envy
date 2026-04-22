package com.envy.dotenv.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.services.PsiEnvExtractor

class DuplicateKeyInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Duplicate key in .env file"
    override fun getGroupDisplayName(): String = "DotEnv"
    override fun getShortName(): String = "DotEnvDuplicateKey"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DotEnvFile) return

                val entries = PsiEnvExtractor.extractEntries(file)
                val document = com.intellij.psi.PsiDocumentManager.getInstance(holder.project).getDocument(file) ?: return
                val seen = mutableMapOf<String, Int>() // key -> first occurrence (1-indexed line)

                for (entry in entries) {
                    val lineNum = document.getLineNumber(entry.keyNode.startOffset) + 1

                    if (seen.containsKey(entry.key)) {
                        holder.registerProblem(
                            entry.keyNode.psi,
                            "Duplicate key '${entry.key}' - first defined on line ${seen[entry.key]}",
                            ProblemHighlightType.WARNING,
                            RemoveDuplicateFix(entry.key)
                        )
                    } else {
                        seen[entry.key] = lineNum
                    }
                }
            }
        }
    }
}

class RemoveDuplicateFix(private val key: String) : com.intellij.codeInspection.LocalQuickFix {
    override fun getName(): String = "Remove duplicate '$key'"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        val lineIndex = document.getLineNumber(element.textOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)

        val chars = document.charsSequence
        val textLength = document.textLength

        // Handle both \n and \r\n line endings
        val deleteEnd = when {
            lineEnd + 1 < textLength && chars[lineEnd] == '\r' && chars[lineEnd + 1] == '\n' -> lineEnd + 2
            lineEnd < textLength && (chars[lineEnd] == '\n' || chars[lineEnd] == '\r') -> lineEnd + 1
            else -> lineEnd
        }

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(lineStart, deleteEnd)
        }
    }
}
