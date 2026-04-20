package com.envy.dotenv

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.envy.dotenv.language.DotEnvLexer
import com.envy.dotenv.language.psi.DotEnvTypes

class DotEnvLexerTest : BasePlatformTestCase() {

    private fun tokenize(text: String, initialState: Int = 0): List<Pair<Any?, String>> {
        val lexer = DotEnvLexer()
        lexer.start(text, 0, text.length, initialState)
        val tokens = mutableListOf<Pair<Any?, String>>()
        while (lexer.tokenType != null) {
            tokens.add(lexer.tokenType to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return tokens
    }

    fun testBasicKeyValue() {
        val tokens = tokenize("KEY=value")
        assertEquals(DotEnvTypes.KEY, tokens[0].first)
        assertEquals("KEY", tokens[0].second)
        assertEquals(DotEnvTypes.SEPARATOR, tokens[1].first)
        assertEquals(DotEnvTypes.VALUE, tokens[2].first)
        assertEquals("value", tokens[2].second)
    }

    fun testCommentLine() {
        val tokens = tokenize("# this is a comment\nKEY=val")
        assertEquals(DotEnvTypes.COMMENT, tokens[0].first)
        assertEquals(DotEnvTypes.KEY, tokens[2].first)
    }

    fun testExportKeyword() {
        val tokens = tokenize("export KEY=value")
        assertEquals(DotEnvTypes.EXPORT, tokens[0].first)
        assertEquals("export", tokens[0].second)
        assertEquals(DotEnvTypes.KEY, tokens[2].first)
        assertEquals("KEY", tokens[2].second)
    }

    fun testQuotedValue() {
        val tokens = tokenize("KEY=\"hello world\"")
        assertEquals(DotEnvTypes.QUOTED_VALUE, tokens[2].first)
        assertEquals("\"hello world\"", tokens[2].second)
    }

    fun testHashInsideValueIsNotComment() {
        val tokens = tokenize("KEY=val#notacomment")
        val value = tokens.find { it.first == DotEnvTypes.VALUE }
        assertEquals("val#notacomment", value?.second)
    }

    // Regression test for the initialState bug fix
    fun testIncrementalRelexRestoresValueState() {
        val lexer = DotEnvLexer()
        val fullText = "KEY=value"
        // Simulate incremental relex starting at position 4 (after '=') with state=1 (afterSeparator)
        lexer.start(fullText, 4, fullText.length, 1)
        assertEquals(DotEnvTypes.VALUE, lexer.tokenType)
        assertEquals("value", fullText.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    fun testIncrementalRelexRestoresKeyState() {
        val lexer = DotEnvLexer()
        val fullText = "KEY=value\nKEY2=val2"
        // Start at second line with state=0 (not afterSeparator)
        lexer.start(fullText, 10, fullText.length, 0)
        assertEquals(DotEnvTypes.KEY, lexer.tokenType)
        assertEquals("KEY2", fullText.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    fun testStateAfterNewlineIsZero() {
        val lexer = DotEnvLexer()
        val text = "KEY=value\nNEXT"
        lexer.start(text, 0, text.length, 0)
        // KEY, SEPARATOR, VALUE, NEWLINE, KEY
        var type = lexer.tokenType
        while (type != null && type != DotEnvTypes.NEWLINE) {
            lexer.advance()
            type = lexer.tokenType
        }
        // Now at the NEWLINE token — advance past it, state should reset to 0
        assertNotNull("Expected NEWLINE token", type)
        lexer.advance()
        assertEquals(0, lexer.state)
        assertEquals(DotEnvTypes.KEY, lexer.tokenType)
    }
}
