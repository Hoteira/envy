package com.envy.dotenv.inspections

import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.language.psi.DotEnvTypes
import com.envy.dotenv.services.PsiEnvExtractor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

class SecretLeakInspection : LocalInspectionTool() {

    companion object {
        private const val CONNECTION_STRING = "Connection string with password"
        private const val KEY_NAME_HEURISTIC = "Possible secret (sensitive key name)"

        private val secretPatterns = listOf(
            SecretPattern("AWS Access Key", Regex("AKIA[0-9A-Z]{16}")),
            SecretPattern("Stripe Secret Key", Regex("sk_(live|test)_[0-9a-zA-Z]{16,}")),
            SecretPattern("Stripe Restricted Key", Regex("rk_(live|test)_[0-9a-zA-Z]{16,}")),
            SecretPattern("GitHub Token", Regex("gh[pousr]_[A-Za-z0-9_]{36,}")),
            SecretPattern("GitHub Fine-grained Token", Regex("github_pat_[A-Za-z0-9_]{22,}")),
            SecretPattern("Anthropic API Key", Regex("sk-ant-[A-Za-z0-9_\\-]{20,}")),
            SecretPattern("OpenAI API Key", Regex("sk-[a-zA-Z0-9_\\-]{20,}")),
            SecretPattern("Google API Key", Regex("AIza[0-9A-Za-z_\\-]{35}")),
            SecretPattern("Hugging Face Token", Regex("hf_[A-Za-z0-9]{30,}")),
            SecretPattern("npm Token", Regex("npm_[A-Za-z0-9]{36}")),
            SecretPattern("SendGrid API Key", Regex("SG\\.[a-zA-Z0-9_-]{6,}\\.[a-zA-Z0-9_-]{20,}")),
            SecretPattern("Slack Token", Regex("xox[baprs]-[0-9a-zA-Z-]{10,}")),
            SecretPattern("JSON Web Token", Regex("eyJ[A-Za-z0-9-_]+\\.eyJ[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+")),
            SecretPattern("Private Key", Regex("-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")),
        )

        /** Patterns that are routinely public under a public key name (Supabase anon keys are JWTs). */
        private val publicSafePatternNames = setOf("JSON Web Token")

        // scheme://[user]:password@host  (password may itself contain '@'; the last '@' wins)
        private val connectionStringRegex = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s:/@]*:[^\s/]+@[^\s/@]""")
        private val schemeRegex = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*""")
        private val queryPasswordRegex = Regex("""[?&;](?:password|pwd|pass)=([^&;#\s]+)""", RegexOption.IGNORE_CASE)
        // ${DB_PASSWORD} or $DB_PASSWORD (upper-case only, so a password like "$ecret1" still counts)
        private val interpolationRegex = Regex("""^\$(\{[A-Za-z_][A-Za-z0-9_]*}|[A-Z_][A-Z0-9_]*)$""")

        // Words that mark a key as holding a credential anywhere in its name.
        private val strongKeyWords = listOf(
            "SECRET", "PASSWORD", "PASSWD", "TOKEN", "CREDENTIAL",
            "PRIVATE_KEY", "PRIVATEKEY", "API_KEY", "APIKEY", "ACCESS_KEY", "ACCESSKEY", "SECRETKEY"
        )

        // Short words that only count as a whole name segment: KEY in API_KEY, not in MONKEY or KEYBOARD;
        // AUTH in BASIC_AUTH, not in AUTHOR.
        private val segmentKeyWords = setOf("KEY", "AUTH", "PWD", "PSW", "PASS", "PASSPHRASE")

        // Values under these names are public by design (NEXT_PUBLIC_*, *_PUBLISHABLE_KEY, *_ANON_KEY).
        private val publicMarkers = setOf("PUBLIC", "PUBLISHABLE", "ANON")

        private val placeholderValues = setOf(
            "", "changeme", "change_me", "xxx", "your_key_here", "todo",
            "replace_me", "placeholder", "null", "none", "undefined"
        )

        private val placeholderPasswords = setOf(
            "password", "pass", "passwd", "changeme", "change_me", "secret", "xxx", "xxxx", "****",
            "your_password", "yourpassword", "your_password_here", "placeholder", "todo"
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

        // Masking these in a terminal would hide ordinary words everywhere (`psql -U postgres`).
        private val commonMaskWords = setOf(
            "postgres", "postgresql", "mysql", "mariadb", "redis", "mongo", "mongodb", "admin",
            "administrator", "root", "user", "username", "password", "secret", "test", "testing",
            "dev", "development", "local", "localhost", "guest", "default", "example", "changeme",
            "sqlserver", "oracle", "rabbitmq", "docker"
        )
        private val singleLowercaseWord = Regex("^[a-z]{1,12}$")

        private fun looksLikeNonSecretValue(value: String): Boolean {
            val v = value.trim()
            if (v.lowercase() in nonSecretValues) return true
            if (v.isNotEmpty() && v.all { it.isDigit() }) return true   // counts, ports, ttls
            if (durationRegex.matches(v)) return true                   // 30s, 1h, 500ms
            return false
        }

        private fun keySegments(upperKey: String): List<String> =
            upperKey.split('_', '-', '.').filter { it.isNotEmpty() }

        private fun isPublicKeyName(upperKey: String): Boolean =
            keySegments(upperKey).any { it in publicMarkers }

        private fun isSensitiveKeyName(upperKey: String): Boolean =
            strongKeyWords.any { upperKey.contains(it) } || keySegments(upperKey).any { it in segmentKeyWords }

        fun getSecretPatternName(value: String): String? {
            if (value.isEmpty()) return null
            secretPatterns.find { it.regex.containsMatchIn(value) }?.let { return it.name }
            if (connectionStringPassword(value) != null) return CONNECTION_STRING
            return null
        }

        fun isSecret(key: String, value: String): Boolean {
            if (value.isEmpty()) return false
            return computeIsSecret(key, value)
        }

        /**
         * Cheap check used to decide whether an upgrade prompt is relevant for the file in front
         * of the user. Pattern-only (no key heuristics) so it stays fast over raw document text and
         * never produces editor warnings on its own.
         */
        fun textContainsSecret(text: String): Boolean {
            if (text.isEmpty()) return false
            return secretPatterns.any { it.regex.containsMatchIn(text) } || connectionStringRegex.containsMatchIn(text)
        }

        /**
         * The password inside a connection string (`postgres://user:pw@host/db`,
         * `redis://:pw@host`, `...?password=pw`), or null when there is none or it is a placeholder
         * or a variable reference.
         */
        fun connectionStringPassword(value: String): String? {
            val v = value.trim()
            val schemeEnd = v.indexOf("://")
            if (schemeEnd <= 0 || !schemeRegex.matches(v.substring(0, schemeEnd))) {
                return queryPassword(v)
            }
            val rest = v.substring(schemeEnd + 3)
            // Credentials end at the last '@' before the path. Passwords often contain '#', '?' or
            // even '@', so those can't be used as delimiters; '/' can (it must be %-encoded there).
            val firstSlash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val at = if (firstSlash == 0) -1 else rest.lastIndexOf('@', firstSlash - 1)
            if (at > 0) {
                val userInfo = rest.substring(0, at)
                val colon = userInfo.indexOf(':')
                if (colon >= 0) {
                    val password = userInfo.substring(colon + 1)
                    if (isLiteralPassword(password)) return password
                }
            }
            return queryPassword(v)
        }

        private fun queryPassword(v: String): String? =
            queryPasswordRegex.find(v)?.groupValues?.get(1)?.takeIf { isLiteralPassword(it) }

        private fun isLiteralPassword(password: String): Boolean {
            if (password.isEmpty()) return false
            if (interpolationRegex.matches(password)) return false        // ${DB_PASSWORD}
            if (password.startsWith("<") && password.endsWith(">")) return false
            if (password.lowercase() in placeholderPasswords) return false
            return true
        }

        private fun computeIsSecret(key: String, value: String): Boolean {
            val upperKey = key.uppercase()
            val publicName = isPublicKeyName(upperKey)

            // High-confidence pattern match wins regardless of the key name, except for patterns
            // that are public by design under a public name (a Supabase anon key is a JWT).
            val pattern = getSecretPatternName(value)
            if (pattern != null) return !(publicName && pattern in publicSafePatternNames)

            if (publicName) return false
            if (!isSensitiveKeyName(upperKey)) return false

            // The key mentions a credential but is clearly a setting about one, not the value.
            if (configKeyMarkers.any { upperKey.contains(it) }) return false

            val lowerValue = value.lowercase().trim()
            if (lowerValue in placeholderValues) return false
            if (lowerValue.startsWith("your_") && lowerValue.endsWith("_here")) return false
            if (lowerValue.startsWith("pk_live_") || lowerValue.startsWith("pk_test_")) return false // publishable
            if (interpolationRegex.matches(value.trim())) return false                              // ${OTHER_VAR}
            if (looksLikeNonSecretValue(value)) return false

            return value.length >= 6
        }

        /**
         * Exact strings the terminal and console censor should mask for this entry. Empty when the
         * entry isn't a secret. Connection strings contribute only their password (the host stays
         * readable), multi-line values (PEM keys) contribute each body line, and values too weak to
         * mask without hiding ordinary words (`postgres`, `admin`) are left out.
         */
        fun censorTargets(key: String, value: String): List<String> {
            if (!isSecret(key, value)) return emptyList()
            val out = linkedSetOf<String>()

            val password = connectionStringPassword(value)
            if (password != null) {
                if (!isWeakForMasking(password)) {
                    out += password
                    if ('%' in password) {
                        try {
                            out += java.net.URLDecoder.decode(password, Charsets.UTF_8)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                }
                return out.toList()
            }

            if (value.contains('\n')) {
                for (line in value.lines()) {
                    val t = line.trim()
                    if (t.startsWith("-----")) continue      // PEM header/footer
                    if (t.length >= 16 && !isWeakForMasking(t)) out += t
                }
                return out.toList()
            }

            if (!isWeakForMasking(value)) out += value
            return out.toList()
        }

        fun isWeakForMasking(value: String): Boolean {
            val t = value.trim()
            if (t.length < 6) return true
            if (t.all { it.isDigit() }) return true
            val lower = t.lowercase()
            if (lower in commonMaskWords) return true
            if (singleLowercaseWord.matches(t)) return true
            return false
        }
    }

    override fun getDisplayName(): String = "Possible secret or credential in .env file"
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

    private enum class VcsState { NO_VCS, IGNORED, UNTRACKED, TRACKED }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is DotEnvFile) return
                val settings = com.envy.dotenv.settings.EnvySettings.getInstance().state
                if (!settings.secretLeakDetection) return

                val vFile = file.virtualFile ?: return
                val project = file.project
                val fileName = vFile.name

                val vcs = vcsState(project, vFile)
                if (vcs == VcsState.IGNORED) return

                val committedByName = fileName in committedFileNames
                val committedByConvention = !committedByName && isCommittedDefaultsFile(vFile)

                val secretEntries = PsiEnvExtractor.extractEntries(file).mapNotNull { entry ->
                    if (!isSecret(entry.key, entry.value)) return@mapNotNull null
                    entry to (getSecretPatternName(entry.value) ?: KEY_NAME_HEURISTIC)
                }
                if (secretEntries.isEmpty()) return
                com.envy.dotenv.engagement.EngagementTracker.getInstance().onIssueCaught()

                // GENERIC_ERROR_OR_WARNING: the level configured in Settings | Inspections applies.
                val type = ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                for ((entry, what) in secretEntries) {
                    val psi = entry.keyNode.psi
                    when {
                        committedByName -> holder.registerProblem(
                            psi,
                            "$what in '${entry.key}' - this file is typically committed to version control",
                            type, ReplaceWithPlaceholderFix(entry.key)
                        )

                        // .env next to .env.local (Symfony, Next.js): .env holds committed defaults and
                        // generated values like APP_SECRET by design. Only flag real credential formats.
                        committedByConvention -> if (what != KEY_NAME_HEURISTIC) holder.registerProblem(
                            psi,
                            "$what in '${entry.key}' - '.env' is shared by convention here; real values belong in .env.local",
                            type, ReplaceWithPlaceholderFix(entry.key)
                        )

                        vcs == VcsState.NO_VCS -> {} // nothing to leak into

                        vcs == VcsState.TRACKED -> holder.registerProblem(
                            psi,
                            "$what in '${entry.key}' - '$fileName' is tracked by git, so this value is in your repository",
                            type, StopTrackingFix(fileName)
                        )

                        else -> holder.registerProblem(
                            psi,
                            "$what in '${entry.key}' - this file is not gitignored",
                            type, AddToGitignoreFix(fileName)
                        )
                    }
                }
            }
        }
    }

    private fun vcsState(project: Project, file: VirtualFile): VcsState {
        if (ProjectLevelVcsManager.getInstance(project).getVcsFor(file) == null) return VcsState.NO_VCS
        val changes = ChangeListManager.getInstance(project)
        if (changes.isIgnoredFile(file)) return VcsState.IGNORED
        if (changes.isUnversioned(file)) return VcsState.UNTRACKED
        return when (FileStatusManager.getInstance(project).getStatus(file)) {
            FileStatus.IGNORED -> VcsState.IGNORED
            FileStatus.UNKNOWN -> VcsState.UNTRACKED
            else -> VcsState.TRACKED
        }
    }

    private fun isCommittedDefaultsFile(file: VirtualFile): Boolean {
        if (file.name != ".env") return false
        val dir = file.parent ?: return false
        return dir.findChild(".env.local") != null || dir.findChild("symfony.lock") != null
    }

    data class SecretPattern(val name: String, val regex: Regex)
}

/** Appends a pattern to a .gitignore unless an equivalent line is already there. */
internal object GitignoreWriter {
    fun addEntry(project: Project, dir: VirtualFile, pattern: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val gitignore = dir.findChild(".gitignore")
            if (gitignore == null) {
                dir.createChildData(this, ".gitignore").setBinaryContent("$pattern\n".toByteArray(Charsets.UTF_8))
                return@runWriteCommandAction
            }
            val psi = PsiManager.getInstance(project).findFile(gitignore) ?: return@runWriteCommandAction
            val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@runWriteCommandAction
            val chars = doc.charsSequence
            val existing = chars.lines().map { it.trim() }
            if (pattern in existing || "/$pattern" in existing) return@runWriteCommandAction
            val newLine = if (chars.isNotEmpty() && chars[chars.length - 1] != '\n') "\n" else ""
            doc.insertString(doc.textLength, "$newLine$pattern\n")
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
    }

    /** The VCS root for [file] if there is one, else the project directory. */
    fun dirFor(project: Project, file: VirtualFile?): VirtualFile? =
        file?.let { ProjectLevelVcsManager.getInstance(project).getVcsRootFor(it) } ?: project.guessProjectDir()
}

class AddToGitignoreFix(private val fileName: String) : LocalQuickFix {
    override fun getName(): String = "Add '$fileName' to .gitignore"
    override fun getFamilyName(): String = "DotEnv"
    override fun startInWriteAction(): Boolean = false

    // Edits a different file (.gitignore), so the in-editor preview can't show it.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile?.virtualFile
        val dir = GitignoreWriter.dirFor(project, file) ?: return
        GitignoreWriter.addEntry(project, dir, fileName)
        file?.let { VcsDirtyScopeManager.getInstance(project).fileDirty(it) }
    }
}

/**
 * For a .env file that git already tracks: adding it to .gitignore does nothing until it is
 * removed from the index. Runs `git rm --cached` (the file stays on disk), makes sure it is
 * ignored, and reminds the user that the values are still in git history.
 */
class StopTrackingFix(private val fileName: String) : LocalQuickFix {
    override fun getName(): String = "Stop tracking '$fileName' in git (keeps the file on disk)"
    override fun getFamilyName(): String = "DotEnv"
    override fun startInWriteAction(): Boolean = false

    // Runs git; must never run as part of a preview.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val root = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(file) ?: return
        val relativePath = VfsUtilCore.getRelativePath(file, root) ?: return

        object : Task.Backgroundable(project, "Removing $fileName from the git index", false) {
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                val cmd = GeneralCommandLine("git", "rm", "--cached", "--quiet", "--", relativePath)
                    .withWorkDirectory(root.path)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                error = try {
                    val out = CapturingProcessHandler(cmd).runProcess(30_000)
                    if (out.exitCode == 0) null else out.stderr.ifBlank { "git exited with code ${out.exitCode}" }
                } catch (e: Exception) {
                    e.message ?: "Couldn't run git"
                }
            }

            override fun onSuccess() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("EnvY")
                val failure = error
                if (failure != null) {
                    group.createNotification("Couldn't stop tracking $fileName", failure, NotificationType.ERROR).notify(project)
                    return
                }
                if (!ChangeListManager.getInstance(project).isIgnoredFile(file)) {
                    GitignoreWriter.addEntry(project, root, fileName)
                }
                VcsDirtyScopeManager.getInstance(project).fileDirty(file)
                group.createNotification(
                    "$fileName is no longer tracked",
                    "Commit this change to remove it from the repository. Its values are still in git history, so rotate any real secrets it contained.",
                    NotificationType.INFORMATION
                ).notify(project)
            }
        }.queue()
    }
}

/** Replaces the entry's value (all of it, even when it spans lines) with a placeholder. */
class ReplaceWithPlaceholderFix(private val key: String) : LocalQuickFix {
    override fun getName(): String = "Replace with placeholder"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val keyElement = descriptor.psiElement ?: return
        val file = keyElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val entry = keyElement.node.treeParent ?: return
        val placeholder = "your_${key.lowercase()}_here"

        var separatorEnd = -1
        var node = entry.firstChildNode
        while (node != null) {
            when (node.elementType) {
                DotEnvTypes.SEPARATOR -> if (separatorEnd < 0) separatorEnd = node.textRange.endOffset
                DotEnvTypes.VALUE, DotEnvTypes.QUOTED_VALUE -> if (separatorEnd >= 0) {
                    document.replaceString(node.textRange.startOffset, node.textRange.endOffset, placeholder)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    return
                }
            }
            node = node.treeNext
        }
        if (separatorEnd >= 0) {
            document.insertString(separatorEnd, placeholder)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}
