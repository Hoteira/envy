package com.envy.dotenv.language.psi

import com.intellij.psi.tree.IElementType
import com.envy.dotenv.language.DotEnvLanguage

class DotEnvTokenType(debugName: String) : IElementType(debugName, DotEnvLanguage)
class DotEnvElementType(debugName: String) : IElementType(debugName, DotEnvLanguage)

object DotEnvTypes {
    val COMMENT = DotEnvTokenType("COMMENT")
    val KEY = DotEnvTokenType("KEY")
    val SEPARATOR = DotEnvTokenType("SEPARATOR")
    val VALUE = DotEnvTokenType("VALUE")
    val QUOTED_VALUE = DotEnvTokenType("QUOTED_VALUE")
    val EXPORT = DotEnvTokenType("EXPORT")
    // Separate token for line breaks so PsiBuilder doesn't swallow them as whitespace
    val NEWLINE = DotEnvTokenType("NEWLINE")
    val ENTRY = DotEnvElementType("ENTRY")
}