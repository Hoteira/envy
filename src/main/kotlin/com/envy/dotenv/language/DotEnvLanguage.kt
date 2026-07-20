package com.envy.dotenv.language

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon
import com.intellij.icons.AllIcons

// Language ID must be globally unique across all installed plugins. The generic
// "DotEnv" collides with ru.adelf.idea.dotenv (the ".env files" plugin) and throws
// ImplementationConflictException on startup, so we namespace it under "EnvY-".
object DotEnvLanguage : Language("EnvY-DotEnv")

object DotEnvFileType : LanguageFileType(DotEnvLanguage) {
    // FileType name is likewise a global unique key that collides with ru.adelf's "DotEnv".
    override fun getName(): String = "EnvY DotEnv"
    override fun getDescription(): String = "Environment variables file"
    override fun getDefaultExtension(): String = "env"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}