package com.envy.dotenv.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.envy.dotenv.language.psi.DotEnvFile
import com.envy.dotenv.licensing.LicenseChecker
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.guessProjectDir

class AddToGitignoreFix(private val fileName: String) : com.intellij.codeInspection.LocalQuickFix {
    override fun getName(): String = "Add '$fileName' to .gitignore"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        val baseDir = project.guessProjectDir() ?: return

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val gitignore = baseDir.findChild(".gitignore")
            if (gitignore != null) {
                // Append to existing .gitignore
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(com.intellij.psi.PsiManager.getInstance(project).findFile(gitignore) ?: return@runWriteCommandAction)
                    ?: return@runWriteCommandAction

                val currentText = doc.text
                val newLine = if (currentText.endsWith("\n")) "" else "\n"
                doc.insertString(doc.textLength, "$newLine$fileName\n")
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc)
            } else {
                // Create .gitignore
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
            SecretPattern("Stripe Secret Key", Regex("sk_(live|test)_[0-9a-zA-Z]{24,}")),
            SecretPattern("Stripe Restricted Key", Regex("rk_(live|test)_[0-9a-zA-Z]{24,}")),
            SecretPattern("GitHub Token", Regex("gh[pousr]_[A-Za-z0-9_]{36,}")),
            SecretPattern("OpenAI API Key", Regex("sk-[a-zA-Z0-9-_]{20,}")),
            SecretPattern("SendGrid API Key", Regex("SG\\.[a-zA-Z0-9_-]{22}\\.[a-zA-Z0-9_-]{43}")),
            SecretPattern("Slack Token", Regex("xox[baprs]-[0-9a-zA-Z-]{10,}")),
            SecretPattern("JSON Web Token", Regex("eyJ[A-Za-z0-9-_]+\\.eyJ[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+")),
            SecretPattern("Private Key", Regex("-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")),
        )

        private val sensitiveKeyWords = listOf(
            "SECRET", "PASSWORD", "PASSWD", "PWD", "TOKEN", "API_KEY", "APIKEY",
            "PRIVATE_KEY", "CREDENTIAL", "AUTH", "ACCESS_KEY", "CLIENT_SECRET",
            "ENCRYPTION_KEY", "SIGNING_KEY", "DATABASE_URL", "KEY", "SECRET"
        )

        private val placeholderValues = setOf(
            "", "changeme", "change_me", "xxx", "your_key_here", "TODO",
            "replace_me", "placeholder", "null", "none", "undefined"
        )

        fun getSecretPatternName(value: String): String? {
            return secretPatterns.find { it.regex.containsMatchIn(value) }?.name
        }

        fun isSecret(key: String, value: String): Boolean {
            if (value.isEmpty()) return false
            if (getSecretPatternName(value) != null) return true

            val upperKey = key.uppercase()
            val isSensitiveKey = sensitiveKeyWords.any { upperKey.contains(it) }
            if (!isSensitiveKey) return false

            val lowerValue = value.lowercase().trim()
            if (lowerValue in placeholderValues) return false
            if (lowerValue.startsWith("your_") && lowerValue.endsWith("_here")) return false

            return value.length >= 4
        }
    }

    override fun getDisplayName(): String = "Secret leak risk in .env file"
    override fun getGroupDisplayName(): String = "DotEnv"
    override fun getShortName(): String = "DotEnvSecretLeak"
    override fun isEnabledByDefault(): Boolean = true

    // Files that are typically committed to version control
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
                if (!LicenseChecker.isPaidFeatureAvailable()) return

                val vFile = file.virtualFile ?: return
                val fileName = vFile.name
                val text = file.text
                val lines = text.lines()

                val isCommittedFile = fileName in committedFileNames
                val isGitignored = isFileGitignored(vFile, file.project)

                // Scan for secrets
                val secretsFound = mutableListOf<Pair<Int, String>>() // line index, pattern name

                var currentOffset = 0

                for ((index, line) in lines.withIndex()) {
                    val lineLength = line.length
                    val lineStartOffset = currentOffset
                    currentOffset += lineLength + 1

                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                    val effective = if (trimmed.startsWith("export ")) {
                        trimmed.removePrefix("export ").trim()
                    } else {
                        trimmed
                    }

                    val sepIndex = effective.indexOfFirst { it == '=' || it == ':' }
                    if (sepIndex <= 0) continue

                    val key = effective.substring(0, sepIndex).trim()
                    val value = effective.substring(sepIndex + 1).trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")

                    if (value.isEmpty()) continue

                    val patternName = getSecretPatternName(value)
                    if (patternName != null) {
                        secretsFound.add(index to patternName)

                        // Only warn if secrets are in a committed file
                        if (isCommittedFile) {
                            val lineEnd = lineStartOffset + line.length
                            val element = file.findElementAt(lineStartOffset)?.parent ?: file
                            val range = TextRange(lineStartOffset, lineEnd)

                            holder.registerProblem(
                                element,
                                "$patternName detected in '$key' - this file is typically committed to version control",
                                ProblemHighlightType.ERROR,
                                range.shiftLeft(element.textRange.startOffset),
                                ReplaceWithPlaceholderFix(key, index)
                            )
                        }
                    } else if (isSecret(key, value)) {
                        secretsFound.add(index to "Possible secret (sensitive key name)")

                        if (isCommittedFile) {
                            val lineEnd = lineStartOffset + line.length
                            val element = file.findElementAt(lineStartOffset)?.parent ?: file
                            val range = TextRange(lineStartOffset, lineEnd)

                            holder.registerProblem(
                                element,
                                "Possible credential in '$key' - this file is typically committed to version control",
                                ProblemHighlightType.ERROR,
                                range.shiftLeft(element.textRange.startOffset),
                                ReplaceWithPlaceholderFix(key, index)
                            )
                        }
                    }
                }

                // Warn on each secret line if file is not gitignored
                if (!isGitignored && !isCommittedFile) {
                    var offset = 0
                    val lineOffsets = IntArray(lines.size)
                    for (i in lines.indices) {
                        lineOffsets[i] = offset
                        offset += lines[i].length + 1
                    }

                    for ((index, patternName) in secretsFound) {
                        val line = lines[index]
                        val trimmed = line.trim()
                        val effective = if (trimmed.startsWith("export ")) trimmed.removePrefix("export ").trim() else trimmed
                        val sepIndex = effective.indexOfFirst { it == '=' || it == ':' }
                        if (sepIndex <= 0) continue
                        val key = effective.substring(0, sepIndex).trim()

                        val lineStartOffset = lineOffsets[index]
                        val lineEnd = lineStartOffset + line.length
                        val element = file.findElementAt(lineStartOffset)?.parent ?: file
                        val range = TextRange(lineStartOffset, lineEnd)

                        holder.registerProblem(
                            element,
                            "$patternName in '$key' - this file is not gitignored",
                            ProblemHighlightType.ERROR,
                            range.shiftLeft(element.textRange.startOffset),
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

class ReplaceWithPlaceholderFix(private val key: String, private val lineIndex: Int) : com.intellij.codeInspection.LocalQuickFix {
    override fun getName(): String = "Replace with placeholder"
    override fun getFamilyName(): String = "DotEnv"

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        if (lineIndex < 0 || lineIndex >= document.lineCount) return

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
