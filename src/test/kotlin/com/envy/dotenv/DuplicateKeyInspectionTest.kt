package com.envy.dotenv

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.envy.dotenv.inspections.DuplicateKeyInspection

class DuplicateKeyInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(DuplicateKeyInspection::class.java)
    }

    fun testNoDuplicates() {
        myFixture.configureByText(".env", "KEY1=value1\nKEY2=value2\n")
        val highlights = myFixture.doHighlighting()
        assertFalse(highlights.any { it.description?.contains("Duplicate") == true })
    }

    fun testDuplicateDetected() {
        myFixture.configureByText(".env", "KEY=value1\nKEY=value2\n")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Duplicate key 'KEY'") == true })
    }

    fun testExportPrefixDuplicateDetected() {
        myFixture.configureByText(".env", "export KEY=value1\nexport KEY=value2\n")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Duplicate key 'KEY'") == true })
    }

    fun testMixedExportAndPlainDuplicate() {
        myFixture.configureByText(".env", "KEY=value1\nexport KEY=value2\n")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Duplicate key 'KEY'") == true })
    }

    fun testCommentsAndBlankLinesIgnored() {
        myFixture.configureByText(".env", "# comment\n\nKEY=value\n")
        val highlights = myFixture.doHighlighting()
        assertFalse(highlights.any { it.description?.contains("Duplicate") == true })
    }

    // Regression test: CRLF line endings must not cause offset drift
    fun testCrlfLineEndingsNoCrash() {
        myFixture.configureByText(".env", "KEY1=value1\r\nKEY1=value2\r\n")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Duplicate key 'KEY1'") == true })
    }

    fun testColonSeparatorDuplicateDetected() {
        myFixture.configureByText(".env", "KEY:value1\nKEY:value2\n")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Duplicate key 'KEY'") == true })
    }
}
