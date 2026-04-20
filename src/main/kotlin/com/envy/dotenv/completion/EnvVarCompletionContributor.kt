package com.envy.dotenv.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.intellij.icons.AllIcons
import com.envy.dotenv.services.EnvFileService
import com.envy.dotenv.licensing.LicenseChecker
import com.envy.dotenv.inspections.SecretLeakInspection
import com.envy.dotenv.language.DotEnvFileType

class EnvVarCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            EnvVarCompletionProvider()
        )
    }
}

class EnvVarCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        // Quick-check keywords — if none appear in the line, skip regex matching entirely
        private val quickCheckKeywords = arrayOf("env", "ENV", "os.", "getenv", "Getenv", "dotenv", "process", "import.meta")

        private val envAccessPatterns = listOf(
            // JS / TS
            Regex("""process\.env\.(\w*)$"""),
            Regex("""process\.env\[['"](\w*)['"]?\]?$"""),
            Regex("""import\.meta\.env\.(\w*)$"""),
            // Python
            Regex("""os\.environ\[['"](\w*)['"]?\]?$"""),
            Regex("""os\.environ\.get\(['"](\w*)['"]?\)?$"""),
            Regex("""os\.getenv\(['"](\w*)['"]?\)?$"""),
            // Rust
            Regex("""env::var\(['"](\w*)['"]?\)?$"""),
            Regex("""std::env::var\(['"](\w*)['"]?\)?$"""),
            // PHP
            Regex("""getenv\(['"](\w*)['"]?\)?$"""),
            Regex("""\${'$'}_ENV\[['"](\w*)['"]?\]?$"""),
            Regex("""env\(['"](\w*)['"]?\)?$"""),
            // Ruby
            Regex("""ENV\[['"](\w*)['"]?\]?$"""),
            // Go
            Regex("""os\.Getenv\(['"](\w*)['"]?\)?$"""),
            // Java / Kotlin
            Regex("""System\.getenv\(['"](\w*)['"]?\)?$"""),
            // C#
            Regex("""Environment\.GetEnvironmentVariable\(['"](\w*)['"]?\)?$"""),
            // generic
            Regex("""dotenv\[['"](\w*)['"]?\]?$""")
        )
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        if (parameters.originalFile.fileType is DotEnvFileType) return
        if (!LicenseChecker.isPaidFeatureAvailable()) return

        val project = parameters.position.project
        val editor = parameters.editor
        val document = editor.document
        val offset = parameters.offset

        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val textBeforeCursor = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))

        // Early exit: skip regex matching if no relevant keyword in the line
        if (quickCheckKeywords.none { textBeforeCursor.contains(it) }) return

        val matchResult = envAccessPatterns.firstNotNullOfOrNull { it.find(textBeforeCursor) }
            ?: return
        val prefix = matchResult.groupValues[1]

        val service = project.getService(EnvFileService::class.java)
        val keyValues = service.getAllKeyValues()
        val sortedKeys = service.getAllKeysSorted()

        val prefixedResult = result.withPrefixMatcher(prefix)
        for (key in sortedKeys) {
            if (!key.startsWith(prefix, ignoreCase = true)) continue
            val value = keyValues[key] ?: ""
            val typeText = if (SecretLeakInspection.isSecret(key, value)) {
                "***"
            } else {
                if (value.length > 30) value.take(30) + "..." else value
            }

            prefixedResult.addElement(
                LookupElementBuilder.create(key)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText(typeText, true)
                    .withBoldness(true)
            )
        }
    }
}
