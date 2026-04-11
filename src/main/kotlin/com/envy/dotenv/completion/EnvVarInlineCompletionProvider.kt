package com.envy.dotenv.completion

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.util.TextRange
import com.envy.dotenv.services.EnvFileService
import com.envy.dotenv.licensing.LicenseChecker

class EnvVarInlineCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("EnvVarInlineCompletion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return event is InlineCompletionEvent.DocumentChange && LicenseChecker.isPaidFeatureAvailable()
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val document = request.document
        val offset = request.endOffset
        val project = request.editor.project ?: return InlineCompletionSuggestion.Empty

        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val textBeforeCursor = document.getText(TextRange(lineStart, offset))

        // Check if we're in an env access pattern
        val envPatterns = listOf(
            Regex("""process\.env\.(\w*)$"""),
            Regex("""process\.env\[["'](\w*)$"""),
            Regex("""os\.environ\[["'](\w*)$"""),
            Regex("""os\.environ\.get\(["'](\w*)$"""),
            Regex("""os\.getenv\(["'](\w*)$"""),
            Regex("""env::var\(["'](\w*)$"""),
            Regex("""std::env::var\(["'](\w*)$"""),
            Regex("""getenv\(["'](\w*)$"""),
            Regex("""System\.getenv\(["'](\w*)$"""),
            Regex("""os\.Getenv\(["'](\w*)$"""),
            Regex("""ENV\[["'](\w*)$"""),
            Regex("""env\(["'](\w*)$"""),
        )

        var prefix = ""
        var matched = false

        for (pattern in envPatterns) {
            val match = pattern.find(textBeforeCursor)
            if (match != null) {
                prefix = match.groupValues[1]
                matched = true
                break
            }
        }

        if (!matched) return InlineCompletionSuggestion.Empty

        // Get env vars
        val service = project.getService(EnvFileService::class.java)
        val envFiles = service.findEnvFiles()

        val allKeys = mutableSetOf<String>()
        for (file in envFiles) {
            allKeys.addAll(service.parseEnvFile(file).keys)
        }

        // Find best match
        val suggestion = allKeys.sorted()
            .firstOrNull { it.startsWith(prefix, ignoreCase = true) && it != prefix }
            ?: return InlineCompletionSuggestion.Empty

        val remaining = suggestion.removePrefix(prefix)

        return object : InlineCompletionSuggestion {
            override suspend fun getVariants(): List<InlineCompletionVariant> {
                return listOf(
                    InlineCompletionVariant.build {
                        emit(InlineCompletionGrayTextElement(remaining))
                    }
                )
            }
        }
    }
}