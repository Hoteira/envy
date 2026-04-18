package com.envy.dotenv.language

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.lang.PsiBuilder
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.language.psi.DotEnvTypes

class DotEnvParserDefinition : ParserDefinition {

    companion object {
        val FILE = IFileElementType(DotEnvLanguage)
    }

    override fun createLexer(project: Project?): Lexer = DotEnvLexer()

    override fun createParser(project: Project?): PsiParser {
        return object : PsiParser {
            override fun parse(root: com.intellij.psi.tree.IElementType, builder: PsiBuilder): ASTNode {
                val rootMarker = builder.mark()
                while (!builder.eof()) {
                    val tokenType = builder.tokenType
                    if (tokenType == DotEnvTypes.KEY || tokenType == DotEnvTypes.EXPORT) {
                        val entry = builder.mark()
                        // Consume until newline or EOF
                        while (!builder.eof() && builder.tokenType != com.intellij.psi.TokenType.WHITE_SPACE || 
                               (builder.tokenType == com.intellij.psi.TokenType.WHITE_SPACE && !builder.tokenText!!.contains("\n") && !builder.tokenText!!.contains("\r"))) {
                            builder.advanceLexer()
                        }
                        entry.done(DotEnvTypes.ENTRY)
                    } else {
                        builder.advanceLexer()
                    }
                }
                rootMarker.done(root)
                return builder.treeBuilt
            }
        }
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = TokenSet.create(DotEnvTypes.COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement {
        return com.intellij.extapi.psi.ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = DotEnvFile(viewProvider)
}