package com.envy.dotenv

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EnvFileServiceTest : BasePlatformTestCase() {

    private fun parse(text: String): Map<String, String> {
        val file = myFixture.createFile(".env", text)
        val service = com.envy.dotenv.services.EnvFileService(project)
        return service.parseEnvFile(file)
    }

    fun testBasicKeyValue() {
        val result = parse("KEY=value")
        assertEquals("value", result["KEY"])
    }

    fun testMultipleEntries() {
        val result = parse("A=1\nB=2\nC=3")
        assertEquals(3, result.size)
        assertEquals("1", result["A"])
        assertEquals("2", result["B"])
        assertEquals("3", result["C"])
    }

    fun testExportPrefix() {
        val result = parse("export SECRET=hunter2")
        assertEquals("hunter2", result["SECRET"])
    }

    fun testQuotedValueDoubleQuotes() {
        val result = parse("KEY=\"hello world\"")
        assertEquals("hello world", result["KEY"])
    }

    fun testQuotedValueSingleQuotes() {
        val result = parse("KEY='hello world'")
        assertEquals("hello world", result["KEY"])
    }

    fun testColonSeparator() {
        val result = parse("KEY:value")
        assertEquals("value", result["KEY"])
    }

    fun testCommentsIgnored() {
        val result = parse("# comment\nKEY=value\n# another comment")
        assertEquals(1, result.size)
        assertEquals("value", result["KEY"])
    }

    fun testBlankLinesIgnored() {
        val result = parse("\n\nKEY=value\n\n")
        assertEquals(1, result.size)
    }

    fun testDirenvCommandsSkipped() {
        val result = parse(
            "dotenv .env.local\n" +
            "source_env .env\n" +
            "source_up\n" +
            "layout python3\n" +
            "use nix\n" +
            "PATH_add bin\n" +
            "path_add bin\n" +
            "watch_file .env\n" +
            "log_status hello\n" +
            "REAL_KEY=real_value"
        )
        assertEquals(1, result.size)
        assertEquals("real_value", result["REAL_KEY"])
    }

    fun testNoSeparatorLineSkipped() {
        val result = parse("NOSEPARATOR\nKEY=value")
        assertEquals(1, result.size)
        assertEquals("value", result["KEY"])
    }

    fun testValueWithSpaces() {
        val result = parse("KEY = value with spaces")
        assertEquals("value with spaces", result["KEY"])
    }

    fun testLastValueWins() {
        val result = parse("KEY=first\nKEY=second")
        assertEquals("second", result["KEY"])
    }
}
