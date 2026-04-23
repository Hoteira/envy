package com.envy.dotenv.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.ide.ui.UISettings
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.language.psi.DotEnvTypes

class PresentationModeInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Secret value hidden in presentation mode"
    override fun getGroupDisplayName(): String = "DotEnv"
    override fun getShortName(): String = "DotEnvPresentationMode"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DotEnvFile) return
                if (!UISettings.getInstance().presentationMode) return

                for (child in file.node.getChildren(null)) {
                    if (child.elementType != DotEnvTypes.ENTRY) continue
                    visitEntry(child, holder)
                }
            }
        }
    }

    private fun visitEntry(entry: ASTNode, holder: ProblemsHolder) {
        var currentKey: String? = null
        var node = entry.firstChildNode
        while (node != null) {
            when (node.elementType) {
                DotEnvTypes.KEY -> currentKey = node.text
                DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> {
                    val key = currentKey
                    if (key != null) {
                        val value = node.text.removeSurrounding("\"").removeSurrounding("'").removeSurrounding("`")
                        if (value.isNotEmpty() && SecretLeakInspection.isSecret(key, value)) {
                            holder.registerProblem(
                                node.psi,
                                "'$key' is hidden in presentation mode",
                                ProblemHighlightType.INFORMATION,
                                RevealSecretFix(key, node.startOffset),
                                RevealAllSecretsFix()
                            )
                        }
                        currentKey = null
                    }
                }
                DotEnvTypes.NEWLINE -> currentKey = null
            }
            node = node.treeNext
        }
    }
}

class RevealSecretFix(private val key: String, private val valueOffset: Int) : LocalQuickFix {
    override fun getName(): String = "Reveal '$key'"
    override fun getFamilyName(): String = "DotEnv Presentation"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val vFile = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val editors = FileEditorManager.getInstance(project)
            .getEditors(vFile).filterIsInstance<TextEditor>().map { it.editor }
        if (editors.isEmpty()) return

        val state = project.getService(com.envy.dotenv.services.PresentationModeState::class.java)
        var marked = false

        for (editor in editors) {
            editor.foldingModel.runBatchFoldingOperation {
                editor.foldingModel.allFoldRegions
                    .filter { it.placeholderText == "***" && valueOffset in it.startOffset..it.endOffset }
                    .forEach {
                        it.isExpanded = true
                        if (!marked) {
                            state.markRevealed(vFile, editor.document, it.startOffset, it.endOffset)
                            marked = true
                        }
                    }
            }
        }
    }
}

class RevealAllSecretsFix : LocalQuickFix {
    override fun getName(): String = "Reveal all hidden values in this file"
    override fun getFamilyName(): String = "DotEnv Presentation"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val vFile = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val editors = FileEditorManager.getInstance(project)
            .getEditors(vFile).filterIsInstance<TextEditor>().map { it.editor }
        if (editors.isEmpty()) return

        val state = project.getService(com.envy.dotenv.services.PresentationModeState::class.java)
        val revealedOffsets = mutableListOf<Pair<Int, Int>>()

        for (editor in editors) {
            editor.foldingModel.runBatchFoldingOperation {
                editor.foldingModel.allFoldRegions
                    .filter { it.placeholderText == "***" && !it.isExpanded }
                    .forEach {
                        it.isExpanded = true
                        revealedOffsets.add(Pair(it.startOffset, it.endOffset))
                    }
            }
        }
        if (revealedOffsets.isNotEmpty()) {
            state.markAllRevealed(vFile, editors.first().document, revealedOffsets)
        }
    }
}
