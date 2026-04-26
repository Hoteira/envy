package com.envy.dotenv

import com.envy.dotenv.inspections.SecretLeakInspection
import org.junit.Assert.*
import org.junit.Test

class SecretLeakInspectionTest {

    // Pattern detection

    @Test
    fun testDetectsAwsAccessKey() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("AKIA1234567890ABCDEF"))
    }

    @Test
    fun testDetectsStripeLiveKey() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("sk_live_abc123def456ghi789jkl012"))
    }

    @Test
    fun testDetectsStripeTestKey() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("sk_test_abc123def456ghi789jkl012"))
    }

    @Test
    fun testDetectsStripeRestrictedKey() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("rk_live_abc123def456ghi789jkl012"))
    }

    @Test
    fun testDetectsGitHubToken() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij"))
    }

    @Test
    fun testDetectsOpenAiKey() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("sk-proj-abcdefghijklmnopqrstuv"))
    }

    @Test
    fun testDetectsSendGridKey() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("SG.abcdefghijklmnopqrstuv.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqr"))
    }

    @Test
    fun testDetectsSlackToken() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("xoxb-1234567890-abcdefgh"))
    }

    @Test
    fun testDetectsJwt() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123"))
    }

    @Test
    fun testDetectsPrivateKeyHeader() {
        assertNotNull(SecretLeakInspection.getSecretPatternName("-----BEGIN RSA PRIVATE KEY-----"))
    }

    @Test
    fun testDoesNotMatchPlainValues() {
        assertNull(SecretLeakInspection.getSecretPatternName("hello_world"))
        assertNull(SecretLeakInspection.getSecretPatternName("12345"))
        assertNull(SecretLeakInspection.getSecretPatternName("localhost:3000"))
    }

    // isSecret (key + value heuristics)

    @Test
    fun testSensitiveKeyWithRealValue() {
        assertTrue(SecretLeakInspection.isSecret("API_KEY", "abcdef1234"))
        assertTrue(SecretLeakInspection.isSecret("CLIENT_SECRET", "s3cr3tval"))
    }

    @Test
    fun testSensitiveKeyWithPlaceholderValue() {
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "changeme"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "your_key_here"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "TODO"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "placeholder"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "xxx"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "none"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "null"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "undefined"))
    }

    @Test
    fun testSensitiveKeyWithYourXHerePattern() {
        assertFalse(SecretLeakInspection.isSecret("SECRET_KEY", "your_secret_key_here"))
    }

    @Test
    fun testNonSensitiveKeyIgnored() {
        assertFalse(SecretLeakInspection.isSecret("APP_NAME", "my-app"))
        assertFalse(SecretLeakInspection.isSecret("PORT", "3000"))
        assertFalse(SecretLeakInspection.isSecret("HOST", "localhost"))
        assertFalse(SecretLeakInspection.isSecret("DEBUG", "true"))
    }

    @Test
    fun testEmptyValueNeverSecret() {
        assertFalse(SecretLeakInspection.isSecret("API_KEY", ""))
        assertFalse(SecretLeakInspection.isSecret("SECRET", ""))
    }

    @Test
    fun testShortValueNotSecret() {
        assertFalse(SecretLeakInspection.isSecret("TOKEN", "abc"))
    }

    @Test
    fun testPatternMatchOverridesSensitiveKeyCheck() {
        // Even with a non-sensitive key name, pattern match should detect
        assertTrue(SecretLeakInspection.isSecret("WHATEVER", "AKIA1234567890ABCDEF"))
        assertTrue(SecretLeakInspection.isSecret("MY_VAR", "sk_live_abc123def456ghi789jkl012"))
    }

    @Test
    fun testCaseInsensitiveKeyMatching() {
        assertTrue(SecretLeakInspection.isSecret("api_key", "realvalue1234"))
        assertTrue(SecretLeakInspection.isSecret("Api_Key", "realvalue1234"))
        assertTrue(SecretLeakInspection.isSecret("password", "mypassword123"))
    }

    @Test
    fun testCaseInsensitivePlaceholderMatching() {
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "CHANGEME"))
        assertFalse(SecretLeakInspection.isSecret("API_KEY", "Placeholder"))
    }
}
