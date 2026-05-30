package com.envy.dotenv.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.envy.dotenv.language.psi.DotEnvFile
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.guessProjectDir
import com.envy.dotenv.services.PsiEnvExtractor

class AddToGitignoreFix(private val fileName: String) : com.intellij.codeInspection.LocalQuickFix {
    override fun getName(): String = "Add '$fileName' to .gitignore"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        val baseDir = project.guessProjectDir() ?: return

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val gitignore = baseDir.findChild(".gitignore")
            if (gitignore != null) {
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(com.intellij.psi.PsiManager.getInstance(project).findFile(gitignore) ?: return@runWriteCommandAction)
                    ?: return@runWriteCommandAction

                val chars = doc.charsSequence
                val newLine = if (chars.isNotEmpty() && chars[chars.length - 1] == '\n') "" else "\n"
                doc.insertString(doc.textLength, "$newLine$fileName\n")
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc)
            } else {
                baseDir.createChildData(this, ".gitignore").setBinaryContent(
                    "$fileName\n".toByteArray(Charsets.UTF_8)
                )
            }
        }
    }
}

class SecretLeakInspection : LocalInspectionTool() {

    companion object {
        private val secretPatterns = listOf(
            SecretPattern("AWS Access Key", Regex("AKIA[0-9A-Z]{16}")),
            SecretPattern("Stripe Secret Key", Regex("sk_(live|test)_[0-9a-zA-Z]{16,}")),
            SecretPattern("Stripe Restricted Key", Regex("rk_(live|test)_[0-9a-zA-Z]{16,}")),
            SecretPattern("GitHub Token", Regex("gh[pousr]_[A-Za-z0-9_]{36,}")),
            SecretPattern("OpenAI API Key", Regex("sk-[a-zA-Z0-9_\\-]{20,}")),
            SecretPattern("SendGrid API Key", Regex("SG\\.[a-zA-Z0-9_-]{6,}\\.[a-zA-Z0-9_-]{20,}")),
            SecretPattern("Slack Token", Regex("xox[baprs]-[0-9a-zA-Z-]{10,}")),
            SecretPattern("JSON Web Token", Regex("eyJ[A-Za-z0-9-_]+\\.eyJ[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+")),
            SecretPattern("Private Key", Regex("-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")),
        )

        private val sensitiveKeyWords = listOf(
            "SECRET", "PASSWORD", "PASSWD", "PWD", "PSW", "TOKEN",
            "CREDENTIAL", "AUTH", "KEY"
        )

        private val placeholderValues = setOf(
            "", "changeme", "change_me", "xxx", "your_key_here", "todo",
            "replace_me", "placeholder", "null", "none", "undefined"
        )

        // Values that name a state/number rather than carry a credential. These commonly appear
        // under sensitive-looking keys (AUTH_ENABLED=true, TOKEN_TTL=3600) and are not secrets.
        private val nonSecretValues = setOf(
            "true", "false", "yes", "no", "on", "off", "enabled", "disabled", "nil"
        )
        private val durationRegex = Regex("^\\d+(ms|s|m|h|d)$", RegexOption.IGNORE_CASE)

        // Key fragments that describe configuration *about* a credential rather than the
        // credential value itself (e.g. JWT_ALGORITHM, SESSION_TIMEOUT, KEY_FILE).
        private val configKeyMarkers = listOf(
            "_ENABLED", "_DISABLED", "_REQUIRED", "_TTL", "_TIMEOUT", "_EXPIRY", "_EXPIRES",
            "_EXPIRATION", "_TYPE", "_ALGORITHM", "_ALGO", "_LENGTH", "_SIZE", "_HEADER",
            "_PROVIDER", "_STRATEGY", "_ROTATION", "_POLICY", "_FORMAT", "_VERSION",
            "_PATH", "_FILE", "_URL", "_URI", "_NAME"
        )

        private fun looksLikeNonSecretValue(value: String): Boolean {
            val v = value.trim()
            if (v.lowercase() in nonSecretValues) return true
            if (v.isNotEmpty() && v.all { it.isDigit() }) return true   // counts, ports, ttls
            if (durationRegex.matches(v)) return true                   // 30s, 1h, 500ms
            return false
        }

        fun getSecretPatternName(value: String): String? {
            if (value.isEmpty()) return null
            return secretPatterns.find { it.regex.containsMatchIn(value) }?.name
        }

        fun isSecret(key: String, value: String): Boolean {
            if (value.isEmpty()) return false
            return computeIsSecret(key, value)
        }

        /**
         * Cheap, allocation-light check used to decide whether an upgrade prompt is relevant
         * for the file in front of the user. Pattern-only (no key heuristics) so it stays fast
         * over raw document text and never produces editor warnings on its own.
         */
        fun textContainsSecret(text: String): Boolean {
            if (text.isEmpty()) return false
            return secretPatterns.any { it.regex.containsMatchIn(text) }
        }

        private fun computeIsSecret(key: String, value: String): Boolean {
            // High-confidence pattern match always wins, regardless of the key name.
            if (getSecretPatternName(value) != null) return true

            val upperKey = key.uppercase()
            val isSensitiveKey = sensitiveKeyWords.any { upperKey.contains(it) }
            if (!isSensitiveKey) return false

            // The key mentions a credential but is clearly a setting about one, not the value.
            if (configKeyMarkers.any { upperKey.contains(it) }) return false

            val lowerValue = value.lowercase().trim()
            if (lowerValue in placeholderValues) return false
            if (lowerValue.startsWith("your_") && lowerValue.endsWith("_here")) return false
            if (looksLikeNonSecretValue(value)) return false

            return value.length >= 6
        }
    }

    override fun getDisplayName(): String = "Secret leak risk in .env file"
    override fun getGroupDisplayName(): String = "DotEnv"
    override fun getShortName(): String = "DotEnvSecretLeak"
    override fun isEnabledByDefault(): Boolean = true

    private val committedFileNames = setOf(
        ".env.example",
        ".env.template",
        ".env.sample",
        ".env.defaults",
        ".env.dist"
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DotEnvFile) return
                val settings = com.envy.dotenv.settings.EnvySettings.getInstance().state
                if (!settings.secretLeakDetection) return

                val vFile = file.virtualFile ?: return
                val fileName = vFile.name

                val isGitignored = isFileGitignored(vFile, file.project)
                if (isGitignored) return

                val isCommittedFile = fileName in committedFileNames

                val entries = PsiEnvExtractor.extractEntries(file)

                // Scan for secrets
                val secretEntries = mutableListOf<Pair<PsiEnvExtractor.PsiEnvEntry, String>>()

                for (entry in entries) {
                    val patternName = getSecretPatternName(entry.value)
                    if (patternName != null) {
                        secretEntries.add(entry to patternName)

                        if (isCommittedFile) {
                            holder.registerProblem(
                                entry.keyNode.psi,
                                "$patternName detected in '${entry.key}' - this file is typically committed to version control",
                                ProblemHighlightType.ERROR,
                                ReplaceWithPlaceholderFix(entry.key, entry.keyNode.startOffset)
                            )
                        }
                    } else if (isSecret(entry.key, entry.value)) {
                        secretEntries.add(entry to "Possible secret (sensitive key name)")

                        if (isCommittedFile) {
                            holder.registerProblem(
                                entry.keyNode.psi,
                                "Possible credential in '${entry.key}' - this file is typically committed to version control",
                                ProblemHighlightType.ERROR,
                                ReplaceWithPlaceholderFix(entry.key, entry.keyNode.startOffset)
                            )
                        }
                    }
                }

                if (secretEntries.isNotEmpty()) {
                    com.envy.dotenv.engagement.EngagementTracker.getInstance().onIssueCaught()
                }

                // Warn on each secret if file is not committed
                if (!isCommittedFile && settings.secretLeakDetection) {
                    for ((entry, patternName) in secretEntries) {
                        holder.registerProblem(
                            entry.keyNode.psi,
                            "$patternName in '${entry.key}' - this file is not gitignored",
                            ProblemHighlightType.ERROR,
                            AddToGitignoreFix(fileName)
                        )
                    }
                }
            }
        }
    }

    private fun isFileGitignored(file: VirtualFile, project: com.intellij.openapi.project.Project): Boolean {
        return com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project).isIgnoredFile(file)
    }

    data class SecretPattern(val name: String, val regex: Regex)
}

class ReplaceWithPlaceholderFix(private val key: String, private val keyOffset: Int) : com.intellij.codeInspection.LocalQuickFix {
    override fun getName(): String = "Replace with placeholder"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        val lineIndex = document.getLineNumber(keyOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val lineText = document.getText(TextRange(lineStart, lineEnd))

        val sepIndex = lineText.indexOfFirst { it == '=' || it == ':' }
        if (sepIndex <= 0) return

        val separator = lineText[sepIndex]
        val keyPart = lineText.substring(0, sepIndex)
        val placeholder = "your_${key.lowercase()}_here"

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(lineStart, lineEnd, "$keyPart$separator$placeholder")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}
