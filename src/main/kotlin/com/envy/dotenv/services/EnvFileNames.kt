package com.envy.dotenv.services

/**
 * Which files EnvY treats as env files, by name. Used instead of checking EnvY's own file type so
 * EnvY keeps working when another plugin (JetBrains' bundled .env support) owns the `.env` type.
 */
object EnvFileNames {
    fun isEnvFileName(name: String): Boolean =
        name == ".env" || name == ".envrc" || name.startsWith(".env.")

    fun isEnvPath(path: String): Boolean = isEnvFileName(path.substringAfterLast('/'))
}
