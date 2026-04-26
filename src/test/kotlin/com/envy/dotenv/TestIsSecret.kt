package com.envy.dotenv

import org.junit.Assert.assertTrue
import org.junit.Test
import com.envy.dotenv.inspections.SecretLeakInspection

class TestIsSecret {
    @Test
    fun testSecrets() {
        val cases = listOf(
            Pair("STRIPE_SECRET_KEY", "sk_live_51ABC123DEF456GHI789JKL"),
            Pair("OPENAI_API_KEY", "sk-proj-abcdefghijklmnop1234567890"),
            Pair("SENDGRID_API_KEY", "SG.abc123.def456ghi789jklmnopqrstuvwxyz"),
            Pair("AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE"),
            Pair("AWS_SECRET_ACCESS_KEY", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            Pair("GCP_SERVICE_KEY", "AIzaSyA1B2"),
            Pair("PASSWORD", "mypassword123"),
            Pair("DB_PWD", "somepassword123"),
            Pair("MY_PSW", "psw_12345")
        )
        for ((k, v) in cases) {
            assertTrue("$k should be secret", SecretLeakInspection.isSecret(k, v))
        }
    }
}
