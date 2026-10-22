package com.envy.dotenv.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.language.psi.DotEnvTypes
import com.envy.dotenv.services.PsiEnvExtractor

/**
 * When a key is defined more than once, loaders use the LAST value. The earlier definitions are
 * dead, so those are the ones flagged, and the quick fix removes them: the value that actually
 * takes effect never changes.
 */
class DuplicateKeyInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Duplicate key in .env file"
    override fun getGroupDisplayName(): String = "DotEnv"
    override fun getShortName(): String = "DotEnvDuplicateKey"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DotEnvFile) return
                if (!com.envy.dotenv.settings.EnvySettings.getInstance().state.duplicateKeyDetection) return

                val document = PsiDocumentManager.getInstance(holder.project).getDocument(file) ?: return
                val byKey = PsiEnvExtractor.extractEntries(file).groupBy { it.key }

                for ((key, occurrences) in byKey) {
                    if (occurrences.size < 2) continue
                    val effectiveLine = document.getLineNumber(occurrences.last().keyNode.startOffset) + 1
                    for (overridden in occurrences.dropLast(1)) {
                        holder.registerProblem(
                            overridden.keyNode.psi,
                            "Duplicate key '$key' - overridden by the definition on line $effectiveLine",
                            ProblemHighlightType.WARNING,
                            RemoveDuplicateFix(key, effectiveLine)
                        )
                    }
                    com.envy.dotenv.engagement.EngagementTracker.getInstance().onIssueCaught()
                }
            }
        }
    }
}

/** Deletes the whole overridden entry, including every line of a multi-line value and its line break. */
class RemoveDuplicateFix(private val key: String, private val effectiveLine: Int) : LocalQuickFix {
    override fun getName(): String = "Remove this '$key' (line $effectiveLine wins)"
    override fun getFamilyName(): String = "Remove overridden duplicate key"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val keyElement = descriptor.psiElement ?: return
        val file = keyElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        val entry = keyElement.node.treeParent?.takeIf { it.elementType == DotEnvTypes.ENTRY } ?: keyElement.node
        val start = document.getLineStartOffset(document.getLineNumber(entry.startOffset))
        val entryEnd = entry.textRange.endOffset

        val chars = document.charsSequence
        val textLength = document.textLength
        val end = when {
            entryEnd + 1 < textLength && chars[entryEnd] == '\r' && chars[entryEnd + 1] == '\n' -> entryEnd + 2
            entryEnd < textLength && (chars[entryEnd] == '\n' || chars[entryEnd] == '\r') -> entryEnd + 1
            else -> entryEnd
        }

        // startInWriteAction() is true (the default), so the document can be edited directly; this
        // also lets the platform show an accurate preview on a copy of the file.
        document.deleteString(start, end)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        recordWin(project)
    }
}
