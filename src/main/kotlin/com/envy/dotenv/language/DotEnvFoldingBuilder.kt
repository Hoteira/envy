package com.envy.dotenv.language

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.language.psi.DotEnvTypes
import com.envy.dotenv.inspections.SecretLeakInspection

class DotEnvFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root !is DotEnvFile) return emptyArray()

        val descriptors = mutableListOf<FoldingDescriptor>()

        for (child in root.node.getChildren(null)) {
            if (child.elementType != DotEnvTypes.ENTRY) continue
            collectFolds(child, descriptors)
        }

        return descriptors.toTypedArray()
    }

    private fun collectFolds(entry: ASTNode, descriptors: MutableList<FoldingDescriptor>) {
        var currentKey: String? = null
        var node = entry.firstChildNode
        while (node != null) {
            when (node.elementType) {
                DotEnvTypes.KEY -> currentKey = node.text
                DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> {
                    val key = currentKey
                    if (key != null) {
                        val value = node.text.removeSurrounding("\"").removeSurrounding("'")
                        if (value.isNotEmpty() && SecretLeakInspection.isSecret(key, value)) {
                            descriptors.add(FoldingDescriptor(node, node.textRange))
                        }
                        currentKey = null
                    }
                }
                DotEnvTypes.NEWLINE -> currentKey = null  // line boundary resets key
            }
            node = node.treeNext
        }
    }

    override fun getPlaceholderText(node: ASTNode): String = "***"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = UISettings.getInstance().presentationMode
}
