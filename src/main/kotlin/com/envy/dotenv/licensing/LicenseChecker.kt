package com.envy.dotenv.licensing

import com.intellij.ui.LicensingFacade
import java.security.Signature
import java.security.cert.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

object LicenseChecker {

    private const val PRODUCT_CODE = "PENVY"

    private const val KEY_PREFIX = "key:"
    private const val STAMP_PREFIX = "stamp:"
    private const val EVAL_PREFIX = "eval:"

    private val JETBRAINS_CERT = """
        -----BEGIN CERTIFICATE-----
        MIIFOzCCAyOgAwIBAgIJANJssYOyg3nhMA0GCSqGSIb3DQEBCwUAMBgxFjAUBgNV
        BAMMDUpldEJyYWlucyBzLnIubzAeFw0xNTEwMjkxMjQ0NDZaFw0xNzEwMjgxMjQ0
        NDZaMBgxFjAUBgNVBAMMDUpldEJyYWlucyBzLnIubzCCAiIwDQYJKoZIhvcNAQEB
        BQADggIPADCCAgoBggIBALMKaNHba3Dn8J1MmAt8SslKxBnAvMxBSJddJb7XGGDH
        LkFObNwsN47iQKsJU0OGCXF3TaaNVNLLus4cY67MRGM8g3bySPXEm+/VDV4r6cTi
        YzD7TnFP3F8rUE/aLMJ9G1t8m6pe77DNXRGHKBX1B6y/Ly5eLriNO4P/c7ovmbR4
        7Wx0mnQb4RqD0gwWx8bjiG+cJsWR0rPN3DH05BKkFasFkiSvA0GJqlbWHkGJsQDi
        flCWsbOrVChJfLIY+G68ZfyySqVBJai0gcSbtoAqyFavhBpcqd3i8f2JG3CkCECP
        Sd6vy7S3cITWdBNITwidJYLp2RCcP5rBdbVNB7dO8gGV2TFaRiE8eTzIJojkYKF0
        QbWFPINaHD0HfibJLYCsiu5fA3bqAbm4OgbTuiSJBnZNMo2LSx2I0xAHzXVCz5bO
        GhIhH9JOa0r4C7RVi1Ikxibnfz0JiIDEfMLVjOjEALS8ril56McHR0P/MZLncZu/
        wSjJMIBuutfuMAAVENreLDPWNaW9c8jS2AjG2RmGBzNGRGAJwS5ZmQx5t1G8FhDc
        JQD52EgJLMOPTG4mQiEiKjSHqnT0gfFOB6xrBMUb66Sp4GKhtSAM+hpLcEXxzQ0
        BO3VsSZiqEWJFjUBBxe3FNMT/b9OB9slyxMa5VTSoE6tkMmEjTL+BhXlLplNjp3f
        AgMBAAGjdzB1MB0GA1UdDgQWBBSMcM1oHPoIqVIJxH5b/JaL5ynB/jAfBgNVHSME
        GDAWgBSMcM1oHPoIqVIJxH5b/JaL5ynB/jAOBgNVHQ8BAf8EBAMCAQYwEwYDVR0l
        BAwwCgYIKwYBBQUHAwEwDgYDVR0PAQH/BAQDAgGGMA0GCSqGSIb3DQEBCwUAA4IC
        AQBFJcZdZnjSHNuFLLXHN6fE/RzGozd4rJTp8pk2bLU9W7FDQEH+rY7bVJYWanEe
        VEJJp6X3vc7rf1P2D0PtYjD3TGBi6v7ElRITh7a59ZpGkEDmTIJfH7bUJFyEPBQs
        vuX4h06W3T/7zFGVEBqhR5X8z34n6Y6iknNqPHH+3Pv8PFgb+rSBFJ/JEwJVYPxR
        Zb33E+opEBM2x2y1Ip+i8CbNNSmAZ8r6wrGJDdC+JQ7Scxm8J6VDiJaKF48qJr3
        0bZx5vZ7KG7sLvPbeVgf5J1SrMWMGIMRBxbJFSLPjDKCiPxlbP0f7QH3xNbH/hVf
        /1b05j9VJu11G8eTq+MTfXLpvE0nFQ/b4DAP7EMiep6XBaRi5xniqHp7JVIxG1aE
        GwNs7v7+a3dvlJtV7BBt3FJ/x0R9KyA5gQ2MJZO3p0OiN7ynFN7g0Nt7T0Bi9kF
        IZm3GJ7gLRU4/8PoKJn8RiNiPKoChaXkNHgS8CU+bR0Hp28TkNzFJusNi8MIp7IY
        KFksCEUMpbVlRKzLm0b9jAT+T1j6LMXRf+RFRQC15cWz9JKkWFo+FLDP3/hQfhx
        7jDaMQ9d7VvNDHUSTiRr2hUmN7Ck4jB3VfT1gVe62g/Hvj2u/Hrji8wR8sOcnMa
        pifXKl2Z5K+9d6EMhch1B1QIFCZWRX1YO+zT1i9UHAA1sQ==
        -----END CERTIFICATE-----
    """.trimIndent()

    fun isPaidFeatureAvailable(): Boolean {
        // Dev-mode bypass: set -Denvy.dev.pro=true in runIde JVM args
        if (System.getProperty("envy.dev.pro") == "true") return true

        var isValid = false
        try {
            val facade = LicensingFacade.getInstance()

            if (facade != null) {
                if (facade.isEvaluationLicense) {
                    isValid = true
                }
                
                val stamp = facade.getConfirmationStamp(PRODUCT_CODE)
                
                if (stamp != null) {
                    when {
                        stamp.startsWith(KEY_PREFIX) -> {
                            val key = stamp.substring(KEY_PREFIX.length)
                            val parts = key.split("-")

                            val keyValid = isKeyValid(key)
                            if (keyValid) isValid = true
                        }
                        stamp.startsWith(STAMP_PREFIX) -> {
                            val stampValid = isLicenseServerStampValid(stamp.substring(STAMP_PREFIX.length))
                            if (stampValid) isValid = true
                        }
                        stamp.startsWith(EVAL_PREFIX) -> {
                            val evalValid = isEvaluationValid(stamp.substring(EVAL_PREFIX.length))
                            if (evalValid) isValid = true
                        }
                        else -> {

                        }
                    }
                }
            }

        } catch (e: Exception) {
        }

        return isValid
    }

    private fun isKeyValid(key: String): Boolean {
        // Offline key validation
        try {
            val parts = key.split("-")
            if (parts.size < 4) return false

            val licensePartBase64 = parts[1]
            val signatureBase64 = parts[2]
            val certBase64 = parts[3]

            val cert = createCertificate(certBase64)
            val sig = Signature.getInstance("SHA1withRSA")
            sig.initVerify(cert)
            sig.update(Base64.getMimeDecoder().decode(licensePartBase64.toByteArray(StandardCharsets.UTF_8)))

            return sig.verify(Base64.getMimeDecoder().decode(signatureBase64.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            return false
        }
    }

    private fun isLicenseServerStampValid(stamp: String): Boolean {
        if (stamp.length < 32) return false
        return try {
            Base64.getMimeDecoder().decode(stamp)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun isEvaluationValid(evalInfo: String): Boolean {
        if (evalInfo.isEmpty()) return false
        // Eval stamp encodes expiry as milliseconds since epoch
        val expiry = evalInfo.trim().toLongOrNull()
        if (expiry != null) return System.currentTimeMillis() < expiry
        // Unknown format — do not grant access
        return false
    }

    private fun createCertificate(certBase64: String): X509Certificate {
        val certFactory = CertificateFactory.getInstance("X.509")
        val certBytes = Base64.getMimeDecoder().decode(certBase64.toByteArray(StandardCharsets.UTF_8))
        return certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }
}