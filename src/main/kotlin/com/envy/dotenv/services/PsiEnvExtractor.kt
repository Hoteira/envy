package com.envy.dotenv.services

import com.intellij.lang.ASTNode
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.language.psi.DotEnvTypes

/**
 * Extracts env entries directly from the PSI tree, ensuring consistency
 * with what the lexer/parser produces (and what the user sees highlighted).
 * Use this in inspections instead of text-based parsing.
 */
object PsiEnvExtractor {

    data class PsiEnvEntry(
        val key: String,
        val value: String,
        val keyNode: ASTNode,
        val valueNode: ASTNode?
    )

    fun extractEntries(file: DotEnvFile): List<PsiEnvEntry> {
        val entries = mutableListOf<PsiEnvEntry>()
        for (child in file.node.getChildren(null)) {
            if (child.elementType != DotEnvTypes.ENTRY) continue
            extractEntry(child)?.let { entries.add(it) }
        }
        return entries
    }

    private fun extractEntry(entryNode: ASTNode): PsiEnvEntry? {
        var keyNode: ASTNode? = null
        var valueNode: ASTNode? = null
        var node = entryNode.firstChildNode
        while (node != null) {
            when (node.elementType) {
                DotEnvTypes.KEY -> keyNode = node
                DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> valueNode = node
            }
            node = node.treeNext
        }
        val kNode = keyNode ?: return null
        val rawValue = valueNode?.text ?: ""
        val value = rawValue.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("`")
        return PsiEnvEntry(kNode.text, value, kNode, valueNode)
    }
}
