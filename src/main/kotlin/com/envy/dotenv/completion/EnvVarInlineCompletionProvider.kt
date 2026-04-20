package com.envy.dotenv.completion

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.envy.dotenv.services.EnvFileService
import com.envy.dotenv.licensing.LicenseChecker
import com.envy.dotenv.language.DotEnvFileType

class EnvVarInlineCompletionProvider : InlineCompletionProvider {

    companion object {
        // Quick-check keywords — if none appear, skip regex matching entirely
        private val quickCheckKeywords = arrayOf("env", "ENV", "os.", "getenv", "Getenv", "dotenv", "process")

        private val envPatterns = listOf(
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
            Regex("""env\(["'](\w*)$""")
        )
    }

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("EnvVarInlineCompletion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (event !is InlineCompletionEvent.DocumentChange) return false
        if (event.editor.virtualFile?.fileType is DotEnvFileType) return false
        return LicenseChecker.isPaidFeatureAvailable()
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val document = request.document
        val offset = request.endOffset
        val project = request.editor.project ?: return InlineCompletionSuggestion.Empty

        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val textBeforeCursor = document.getText(TextRange(lineStart, offset))

        // Early exit: skip regex matching if no relevant keyword in the line
        if (quickCheckKeywords.none { textBeforeCursor.contains(it) }) {
            return InlineCompletionSuggestion.Empty
        }

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

        val service = project.getService(EnvFileService::class.java)
        val allKeysSorted = readAction { service.getAllKeysSorted() }

        val candidates = allKeysSorted
            .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }

        val suggestion = candidates.firstOrNull() ?: return InlineCompletionSuggestion.Empty
        val remaining = suggestion.substring(prefix.length)

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
