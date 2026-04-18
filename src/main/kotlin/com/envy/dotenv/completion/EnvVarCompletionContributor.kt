package com.envy.dotenv.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.intellij.icons.AllIcons
import com.envy.dotenv.services.EnvFileService
import com.envy.dotenv.licensing.LicenseChecker

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
        // Patterns that indicate the user is accessing an env var
        private val envAccessPatterns = listOf(
            // JavaScript / TypeScript
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

            // C# / .NET
            Regex("""Environment\.GetEnvironmentVariable\(['"](\w*)['"]?\)?$"""),

            // Generic dotenv usage
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

        // Get the text before the cursor on the current line
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val textBeforeCursor = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))

        // Check if any env access pattern matches
        val matches = envAccessPatterns.any { it.containsMatchIn(textBeforeCursor) }
        if (!matches) return

        // Get all env vars from all .env files
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

        // Add completions
        for (key in allKeys.sorted()) {
            val value = keyValues[key] ?: ""
            val displayValue = if (value.length > 30) value.take(30) + "..." else value

            result.addElement(
                LookupElementBuilder.create(key)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText(displayValue, true)
                    .withBoldness(true)
            )
        }
    }
}