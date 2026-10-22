package com.envy.dotenv

import com.envy.dotenv.inspections.SecretLeakInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1: connection strings, public keys, key-name false positives, what the censor masks. */
class SecretDetectionTest {

    @Test
    fun testConnectionStringPasswords() {
        assertEquals("Xk9#mP2vR7qL4", SecretLeakInspection.connectionStringPassword("postgres://app:Xk9#mP2vR7qL4@db:5432/app?sslmode=require"))
        assertEquals("p@ssw0rd!", SecretLeakInspection.connectionStringPassword("postgres://admin:p@ssw0rd!@db.example.com:5432/myapp"))
        assertEquals("s3cretRedisPass99", SecretLeakInspection.connectionStringPassword("redis://:s3cretRedisPass99@redis:6379/0"))
        assertEquals("Hunter22x", SecretLeakInspection.connectionStringPassword("jdbc:postgresql://h/db?user=x&password=Hunter22x"))
    }

    @Test
    fun testConnectionStringsWithoutRealPassword() {
        assertNull(SecretLeakInspection.connectionStringPassword("mongodb://admin:\${MONGO_PASSWORD}@mongo:27017"))
        assertNull(SecretLeakInspection.connectionStringPassword("postgres://user:password@localhost/db"))
        assertNull(SecretLeakInspection.connectionStringPassword("postgres://localhost/db"))
        assertNull(SecretLeakInspection.connectionStringPassword("https://example.com/path?x=a@b"))
    }

    @Test
    fun testDatabaseUrlIsSecretDespiteUrlSuffix() {
        assertTrue(SecretLeakInspection.isSecret("DATABASE_URL", "postgres://app:Xk9#mP2vR7qL4@db:5432/app"))
    }

    @Test
    fun testKeyNameFalsePositivesAreGone() {
        assertFalse(SecretLeakInspection.isSecret("AUTHOR_NAME", "Jane Doe"))
        assertFalse(SecretLeakInspection.isSecret("KEYBOARD_LAYOUT", "dvorak"))
        assertFalse(SecretLeakInspection.isSecret("MONKEY_COUNT", "bananas99"))
    }

    @Test
    fun testPublicValuesAreNotSecrets() {
        assertFalse(SecretLeakInspection.isSecret("NEXT_PUBLIC_API_KEY", "abcdef123456"))
        assertFalse(SecretLeakInspection.isSecret("STRIPE_PUBLISHABLE_KEY", "pk_live_abcdef123456"))
        assertFalse(SecretLeakInspection.isSecret("SUPABASE_ANON_KEY", "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.c2ln"))
        assertFalse(SecretLeakInspection.isSecret("DB_PASSWORD", "\${DB_PASS}"))
    }

    @Test
    fun testRealSecretUnderPublicNameIsStillCaught() {
        assertTrue(SecretLeakInspection.isSecret("NEXT_PUBLIC_STRIPE_SECRET", "sk_live_abcdef1234567890xyz"))
    }

    @Test
    fun testWeakValuesAreDetectedButNotMasked() {
        // Still a credential worth warning about in the editor...
        assertTrue(SecretLeakInspection.isSecret("POSTGRES_PASSWORD", "postgres"))
        // ...but masking it would hide every "postgres" in the terminal.
        assertTrue(SecretLeakInspection.censorTargets("POSTGRES_PASSWORD", "postgres").isEmpty())
        assertTrue(SecretLeakInspection.censorTargets("ADMIN_PASSWORD", "admin").isEmpty())
    }

    @Test
    fun testConnectionStringMasksOnlyThePassword() {
        assertEquals(
            listOf("Xk9#mP2vR7qL4"),
            SecretLeakInspection.censorTargets("DATABASE_URL", "postgres://app:Xk9#mP2vR7qL4@db:5432/app")
        )
    }

    @Test
    fun testPemKeyMasksBodyLinesNotHeaders() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0t\n-----END RSA PRIVATE KEY-----"
        assertEquals(listOf("MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0t"), SecretLeakInspection.censorTargets("PRIVATE_KEY", pem))
    }
}
