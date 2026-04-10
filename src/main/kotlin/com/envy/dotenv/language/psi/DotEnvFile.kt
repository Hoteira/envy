package com.envy.dotenv.language.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.envy.dotenv.language.DotEnvFileType
import com.envy.dotenv.language.DotEnvLanguage

class DotEnvFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DotEnvLanguage) {
    override fun getFileType(): FileType = DotEnvFileType
    override fun toString(): String = "DotEnv File"

}