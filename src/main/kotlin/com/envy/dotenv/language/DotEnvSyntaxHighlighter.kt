package com.envy.dotenv.language

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.envy.dotenv.language.psi.DotEnvTypes
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.psi.TokenType

class DotEnvSyntaxHighlighter : SyntaxHighlighterBase() {
    companion object {
        val KEY = TextAttributesKey.createTextAttributesKey("DOTENV_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val VALUE = TextAttributesKey.createTextAttributesKey("DOTENV_VALUE", DefaultLanguageHighlighterColors.STRING)
        val COMMENT = TextAttributesKey.createTextAttributesKey("DOTENV_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val SEPARATOR = TextAttributesKey.createTextAttributesKey("DOTENV_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val EXPORT = TextAttributesKey.createTextAttributesKey("DOTENV_EXPORT", DefaultLanguageHighlighterColors.METADATA)
    }

    override fun getHighlightingLexer(): Lexer = DotEnvLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return when (tokenType) {
            DotEnvTypes.KEY -> arrayOf(KEY)
            DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> arrayOf(VALUE)
            DotEnvTypes.COMMENT -> arrayOf(COMMENT)
            DotEnvTypes.SEPARATOR -> arrayOf(SEPARATOR)
            DotEnvTypes.EXPORT -> arrayOf(EXPORT)
            DotEnvTypes.NEWLINE -> emptyArray()
            TokenType.BAD_CHARACTER -> arrayOf(HighlighterColors.BAD_CHARACTER)
            else -> emptyArray()
        }
    }
}

class DotEnvSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    private val highlighter by lazy { DotEnvSyntaxHighlighter() }

    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return highlighter
    }
}