package com.envy.dotenv.sops

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger

object SopsCli {

    private val LOG = Logger.getInstance(SopsCli::class.java)
    private const val TIMEOUT_MS = 30_000

    data class Result(val success: Boolean, val output: String, val error: String)

    fun decrypt(sopsPath: String, filePath: String): Result {
        return run(sopsPath, listOf("-d", "--input-type", "dotenv", "--output-type", "dotenv", filePath))
    }

    fun encrypt(sopsPath: String, filePath: String, plaintext: String, keyArgs: List<String> = emptyList()): Result {
        val cmdArgs = mutableListOf(sopsPath, "encrypt", "--input-type", "dotenv", "--output-type", "dotenv", "--filename-override", java.io.File(filePath).name)
        cmdArgs += keyArgs
        val cmd = GeneralCommandLine(cmdArgs)
            .withCharset(Charsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withWorkDirectory(java.io.File(filePath).parentFile)

        return try {
            val handler = CapturingProcessHandler(cmd)
            val process = handler.process
            process.outputStream.use {
                it.write(plaintext.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            val output = handler.runProcess(TIMEOUT_MS)
            if (output.exitCode == 0) {
                Result(true, output.stdout, "")
            } else {
                LOG.warn("sops encrypt failed: ${output.stderr}")
                Result(false, "", output.stderr)
            }
        } catch (e: Exception) {
            LOG.warn("sops encrypt error", e)
            Result(false, "", e.message ?: "Unknown error")
        }
    }

    fun extractKeyArgs(encryptedContent: String): List<String> {
        val args = mutableListOf<String>()

        val pgpFps = Regex("""sops_pgp__list_\d+__map_fp=(.+)""")
            .findAll(encryptedContent).map { it.groupValues[1].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
        if (pgpFps.isNotEmpty()) { args += "--pgp"; args += pgpFps.joinToString(",") }

        val ageRecipients = Regex("""sops_age__list_\d+__map_recipient=(.+)""")
            .findAll(encryptedContent).map { it.groupValues[1].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
        if (ageRecipients.isNotEmpty()) { args += "--age"; args += ageRecipients.joinToString(",") }

        val kmsArnsMap = mutableMapOf<String, String>()
        Regex("""sops_kms__list_(\d+)__map_arn=(.+)""")
            .findAll(encryptedContent).forEach { match ->
                kmsArnsMap[match.groupValues[1]] = match.groupValues[2].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            }
        Regex("""sops_kms__list_(\d+)__map_role=(.+)""")
            .findAll(encryptedContent).forEach { match ->
                val idx = match.groupValues[1]
                val role = match.groupValues[2].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                if (kmsArnsMap.containsKey(idx)) {
                    kmsArnsMap[idx] = kmsArnsMap[idx] + "+" + role
                }
            }
        if (kmsArnsMap.isNotEmpty()) { args += "--kms"; args += kmsArnsMap.values.joinToString(",") }

        val awsProfiles = Regex("""sops_kms__list_\d+__map_aws_profile=(.+)""")
            .findAll(encryptedContent).map { it.groupValues[1].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
        if (awsProfiles.isNotEmpty()) { args += "--aws-profile"; args += awsProfiles.first() }

        val gcpKms = Regex("""sops_gcp_kms__list_\d+__map_resource_id=(.+)""")
            .findAll(encryptedContent).map { it.groupValues[1].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
        if (gcpKms.isNotEmpty()) { args += "--gcp-kms"; args += gcpKms.joinToString(",") }

        // HashiCorp Vault transit: metadata keeps address, engine path and key name separately;
        // the CLI wants one URI per key: <address>/v1/<engine_path>/keys/<key_name>.
        val hcVault = indexedEntries(encryptedContent, "hc_vault").values.mapNotNull { f ->
            val address = f["vault_address"] ?: return@mapNotNull null
            val engine = f["engine_path"] ?: return@mapNotNull null
            val keyName = f["key_name"] ?: return@mapNotNull null
            "${address.trimEnd('/')}/v1/${engine.trim('/')}/keys/$keyName"
        }
        if (hcVault.isNotEmpty()) { args += "--hc-vault-transit"; args += hcVault.joinToString(",") }

        // Azure Key Vault: <vault_url>/keys/<name>/<version>.
        val azureKv = indexedEntries(encryptedContent, "azure_kv").values.mapNotNull { f ->
            val vaultUrl = f["vault_url"] ?: return@mapNotNull null
            val name = f["name"] ?: return@mapNotNull null
            val version = f["version"] ?: return@mapNotNull null
            "${vaultUrl.trimEnd('/')}/keys/$name/$version"
        }
        if (azureKv.isNotEmpty()) { args += "--azure-kv"; args += azureKv.joinToString(",") }

        return args
    }

    /**
     * Everything needed to re-encrypt a file the way it was encrypted: the key flags plus the
     * file's encryption rules (which values are encrypted, Shamir threshold, MAC mode). Without the
     * rules, re-encryption would silently fall back to sops defaults.
     */
    fun extractEncryptArgs(encryptedContent: String): List<String> {
        val args = extractKeyArgs(encryptedContent).toMutableList()
        val meta = securityConfig(encryptedContent)
        for ((metaKey, flag) in RULE_FLAGS) {
            meta[metaKey]?.let { args += flag; args += it }
        }
        if (meta["sops_mac_only_encrypted"]?.equals("true", ignoreCase = true) == true) {
            args += "--mac-only-encrypted"
        }
        return args
    }

    /**
     * The file's SOPS settings that must survive a re-encryption: recipients and key ids, key
     * groups, threshold, encryption rules. Excludes what legitimately changes on every encryption
     * (data keys, MAC, timestamps, sops version) and any user value that is itself encrypted.
     */
    fun securityConfig(content: String): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("sops_")) continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val key = t.substring(0, eq)
            val value = unquote(t.substring(eq + 1))
            if (isVolatileMetadataKey(key) || value.startsWith("ENC[")) continue
            out[key] = value
        }
        return out
    }

    /** Human-readable differences in [securityConfig] between two versions of a file; empty = safe. */
    fun securityConfigDiff(before: String, after: String): List<String> {
        val a = securityConfig(before)
        val b = securityConfig(after)
        return (a.keys + b.keys).toSortedSet().mapNotNull { key ->
            val old = a[key]
            val new = b[key]
            if (old == new) null else "${prettyMetadataKey(key)}: ${old ?: "(none)"} -> ${new ?: "(none)"}"
        }
    }

    private val RULE_FLAGS = listOf(
        "sops_shamir_threshold" to "--shamir-secret-sharing-threshold",
        "sops_encrypted_regex" to "--encrypted-regex",
        "sops_unencrypted_regex" to "--unencrypted-regex",
        "sops_encrypted_suffix" to "--encrypted-suffix",
        "sops_unencrypted_suffix" to "--unencrypted-suffix",
        "sops_encrypted_comment_regex" to "--encrypted-comment-regex",
        "sops_unencrypted_comment_regex" to "--unencrypted-comment-regex",
    )

    private fun isVolatileMetadataKey(key: String): Boolean =
        key == "sops_mac" || key == "sops_lastmodified" || key == "sops_version" ||
            key.endsWith("__map_enc") || key.endsWith("__map_created_at")

    private fun prettyMetadataKey(key: String): String =
        key.removePrefix("sops_")
            .replace(Regex("""__list_(\d+)""")) { "[${it.groupValues[1]}]" }
            .replace("__map_", ".")

    /** Entries of a metadata list (e.g. "hc_vault"), keyed by list index, fields by name. */
    private fun indexedEntries(content: String, list: String): Map<String, Map<String, String>> {
        val re = Regex("""^\s*sops_${list}__list_(\d+)__map_([A-Za-z0-9_]+)=(.*)$""", RegexOption.MULTILINE)
        val out = linkedMapOf<String, MutableMap<String, String>>()
        for (m in re.findAll(content)) {
            out.getOrPut(m.groupValues[1]) { linkedMapOf() }[m.groupValues[2]] = unquote(m.groupValues[3])
        }
        return out
    }

    private fun unquote(raw: String): String =
        raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun run(sopsPath: String, args: List<String>): Result {
        val cmd = GeneralCommandLine(listOf(sopsPath) + args)
            .withCharset(Charsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        return try {
            val handler = CapturingProcessHandler(cmd)
            val output = handler.runProcess(TIMEOUT_MS)
            if (output.exitCode == 0) {
                Result(true, output.stdout, "")
            } else {
                LOG.warn("sops failed (exit ${output.exitCode}): ${output.stderr}")
                Result(false, "", output.stderr)
            }
        } catch (e: Exception) {
            LOG.warn("sops error", e)
            Result(false, "", e.message ?: "Unknown error")
        }
    }
}
