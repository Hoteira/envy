package com.envy.dotenv.sops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SopsCliTest {

    @Test
    fun testExtractKeyArgsAwsKmsRole() {
        val encryptedContent = """
            sops_kms__list_0__map_arn="arn:aws:kms:us-east-1:123456789012:key/12345"
            sops_kms__list_0__map_role="arn:aws:iam::123456789012:role/sops-role"
            sops_kms__list_0__map_aws_profile="my-profile"
        """.trimIndent()

        val args = SopsCli.extractKeyArgs(encryptedContent)

        assertTrue(args.contains("--kms"))
        assertTrue(args.contains("arn:aws:kms:us-east-1:123456789012:key/12345+arn:aws:iam::123456789012:role/sops-role"))
        assertTrue(args.contains("--aws-profile"))
        assertTrue(args.contains("my-profile"))
    }

    // A dotenv file as sops writes it: two age recipients and an encrypted_regex rule.
    private val encrypted = """
        API_KEY=ENC[AES256_GCM,data:abc,iv:x,tag:y,type:str]
        DB_HOST=localhost
        sops_age__list_0__map_enc=-----BEGIN AGE ENCRYPTED FILE-----\nAAA\n-----END AGE ENCRYPTED FILE-----\n
        sops_age__list_0__map_recipient=age1aaa
        sops_age__list_1__map_enc=-----BEGIN AGE ENCRYPTED FILE-----\nBBB\n-----END AGE ENCRYPTED FILE-----\n
        sops_age__list_1__map_recipient=age1bbb
        sops_encrypted_regex=^(API_KEY|DB_PASSWORD)
        sops_lastmodified=2026-01-01T00:00:00Z
        sops_mac=ENC[AES256_GCM,data:mac,iv:x,tag:y,type:str]
        sops_version=3.9.0
    """.trimIndent()

    // Same file re-encrypted with the same settings: only data keys, MAC, timestamp and version change.
    private val reEncrypted = encrypted
        .replace("data:abc", "data:zzz")
        .replace("AAA", "CCC")
        .replace("BBB", "DDD")
        .replace("2026-01-01", "2026-10-22")
        .replace("3.9.0", "3.10.2")
        .replace("data:mac", "data:mac2")

    @Test
    fun testEncryptArgsKeepEncryptionRules() {
        val args = SopsCli.extractEncryptArgs(encrypted)
        assertEquals(
            listOf("--age", "age1aaa,age1bbb", "--encrypted-regex", "^(API_KEY|DB_PASSWORD)"),
            args
        )
    }

    @Test
    fun testEncryptArgsWithWindowsLineEndings() {
        assertEquals(
            SopsCli.extractEncryptArgs(encrypted),
            SopsCli.extractEncryptArgs(encrypted.replace("\n", "\r\n"))
        )
    }

    @Test
    fun testReEncryptionWithSameSettingsIsNotDrift() {
        assertTrue(SopsCli.securityConfigDiff(encrypted, reEncrypted).isEmpty())
    }

    @Test
    fun testDroppedRecipientIsDrift() {
        val dropped = reEncrypted.lines().filterNot { "list_1" in it }.joinToString("\n")
        val diff = SopsCli.securityConfigDiff(encrypted, dropped)
        assertEquals(listOf("age[1].recipient: age1bbb -> (none)"), diff)
    }

    @Test
    fun testChangedEncryptionRuleIsDrift() {
        val changed = reEncrypted.replace("sops_encrypted_regex=^(API_KEY|DB_PASSWORD)", "sops_unencrypted_suffix=_unencrypted")
        val diff = SopsCli.securityConfigDiff(encrypted, changed)
        assertEquals(2, diff.size)
        assertTrue(diff.any { it.startsWith("encrypted_regex:") })
        assertTrue(diff.any { it.startsWith("unencrypted_suffix:") })
    }

    @Test
    fun testVaultAndAzureKeysBecomeCliUris() {
        val content = """
            sops_hc_vault__list_0__map_vault_address=https://vault.example.org:8200
            sops_hc_vault__list_0__map_engine_path=transit/
            sops_hc_vault__list_0__map_key_name=dev
            sops_azure_kv__list_0__map_vault_url=https://sops.vault.azure.net
            sops_azure_kv__list_0__map_name=sops-key
            sops_azure_kv__list_0__map_version=abc123
            sops_unencrypted_suffix=_unencrypted
            sops_mac_only_encrypted=true
        """.trimIndent()

        assertEquals(
            listOf(
                "--hc-vault-transit", "https://vault.example.org:8200/v1/transit/keys/dev",
                "--azure-kv", "https://sops.vault.azure.net/keys/sops-key/abc123",
                "--unencrypted-suffix", "_unencrypted",
                "--mac-only-encrypted",
            ),
            SopsCli.extractEncryptArgs(content)
        )
    }

    @Test
    fun testEncryptedUserValuesAreNotTreatedAsSettings() {
        val config = SopsCli.securityConfig("sops_custom_token=ENC[AES256_GCM,data:x,iv:y,tag:z,type:str]\nsops_version=3.9.0")
        assertFalse(config.containsKey("sops_custom_token"))
        assertTrue(config.isEmpty())
    }
}
