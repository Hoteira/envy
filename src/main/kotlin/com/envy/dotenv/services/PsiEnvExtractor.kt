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

    /**
     * Same rules as EnvParser.parse: the first word is the key, a separator must follow it, and the
     * first value after the separator is the value. Lines like direnv's "layout python3" (two words,
     * no separator) are not entries.
     */
    private fun extractEntry(entryNode: ASTNode): PsiEnvEntry? {
        var keyNode: ASTNode? = null
        var valueNode: ASTNode? = null
        var sawSeparator = false
        var node = entryNode.firstChildNode
        while (node != null) {
            when (node.elementType) {
                DotEnvTypes.KEY -> if (keyNode == null && !sawSeparator) keyNode = node else if (!sawSeparator) return null
                DotEnvTypes.SEPARATOR -> if (keyNode != null) sawSeparator = true
                DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> if (sawSeparator && valueNode == null) valueNode = node
            }
            node = node.treeNext
        }
        val kNode = keyNode ?: return null
        if (!sawSeparator) return null
        val value = EnvParser.decodeValue(valueNode?.text ?: "")
        return PsiEnvEntry(kNode.text, value, kNode, valueNode)
    }
}
