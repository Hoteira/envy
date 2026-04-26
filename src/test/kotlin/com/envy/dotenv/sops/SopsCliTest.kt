package com.envy.dotenv.sops

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
}
