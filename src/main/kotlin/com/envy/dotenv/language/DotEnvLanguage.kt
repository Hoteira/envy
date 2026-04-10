package com.envy.dotenv.language

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon
import com.intellij.icons.AllIcons

object DotEnvLanguage : Language("DotEnv")

object DotEnvFileType : LanguageFileType(DotEnvLanguage) {
    override fun getName(): String = "DotEnv"
    override fun getDescription(): String = "Environment variables file"
    override fun getDefaultExtension(): String = "env"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}