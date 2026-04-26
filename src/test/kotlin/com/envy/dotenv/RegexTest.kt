package com.envy.dotenv

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import com.envy.dotenv.terminal.AhoCorasick

class RegexTest {
    @Test
    fun testRegexes() {
        val stripe = "sk_live_51ABC123DEF456GHI789JKL"
        val openai = "sk-proj-abcdefghijklmnop1234567890"
        val sendgrid = "SG.abc123.def456ghi789jklmnopqrstuvwxyz"
        
        assertTrue("Stripe failed", Regex("sk_(live|test)_[0-9a-zA-Z]{16,}").containsMatchIn(stripe))
        assertTrue("OpenAI failed", Regex("sk-[a-zA-Z0-9-_]{20,}").containsMatchIn(openai))
        assertTrue("SendGrid failed", Regex("SG\\.[a-zA-Z0-9_-]{6,}\\.[a-zA-Z0-9_-]{20,}").containsMatchIn(sendgrid))
    }

    @Test
    fun testAhoCorasick() {
        val secrets = listOf(
            "sk_live_51ABC123DEF456GHI789JKL",
            "sk-proj-abcdefghijklmnop1234567890",
            "SG.abc123.def456ghi789jklmnopqrstuvwxyz",
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "AIzaSyA1B2"
        )
        val ac = AhoCorasick.build(secrets)!!
        val text = """
            STRIPE_SECRET_KEY=sk_live_51ABC123DEF456GHI789JKL
            OPENAI_API_KEY=sk-proj-abcdefghijklmnop1234567890
            AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
            SENDGRID_API_KEY=SG.abc123.def456ghi789jklmnopqrstuvwxyz
            GCP_SERVICE_KEY=AIzaSyA1B2
        """.trimIndent()
        
        val result = ac.scan(text)
        assertEquals("Should find 6 matches", 6, result.matches.size)
    }
}

