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

        val hcVault = Regex("""sops_hc_vault_transit__list_\d+__map_uri=(.+)""")
            .findAll(encryptedContent).map { it.groupValues[1].trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
        if (hcVault.isNotEmpty()) { args += "--hc-vault-transit"; args += hcVault.joinToString(",") }

        return args
    }

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
