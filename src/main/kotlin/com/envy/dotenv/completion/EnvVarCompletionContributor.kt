package com.envy.dotenv.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.intellij.icons.AllIcons
import com.envy.dotenv.services.EnvFileService
import com.envy.dotenv.licensing.LicenseChecker
import com.envy.dotenv.inspections.SecretLeakInspection

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
        if (!LicenseChecker.isPaidFeatureAvailable()) return

        val project = parameters.position.project
        val editor = parameters.editor
        val document = editor.document
        val offset = parameters.offset

        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val textBeforeCursor = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))

        val matchResult = envAccessPatterns.firstNotNullOfOrNull { it.find(textBeforeCursor) }
        if (matchResult == null) return
        val prefix = matchResult.groupValues[1]

        val service = project.getService(EnvFileService::class.java)
        val envFiles = service.findEnvFiles()

        val allKeys = mutableSetOf<String>()
        val keyValues = mutableMapOf<String, String>()

        for (file in envFiles) {
            val parsed = service.parseEnvFile(file)
            for ((key, value) in parsed) {
                if (allKeys.add(key)) {
                    keyValues[key] = value
                }
            }
        }

        val prefixedResult = result.withPrefixMatcher(prefix)
        for (key in allKeys.sorted()) {
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