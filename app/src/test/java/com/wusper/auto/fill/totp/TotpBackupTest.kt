package com.wusper.auto.fill.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpBackupTest {

    private val sample = listOf(
        TotpAccount(
            id = "a1",
            issuer = "Mail",
            label = "me@example.com",
            secretBase32 = "JBSWY3DPEHPK3PXP"
        ),
        TotpAccount(
            id = "a2",
            issuer = "Bank",
            label = "login",
            secretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            algorithm = TotpAlgorithm.SHA256,
            digits = 8,
            periodSeconds = 60
        )
    )

    @Test
    fun plainJsonRoundTrip() {
        val json = TotpBackup.toPlainJson(sample)
        assertTrue(json.contains("autofill-totp"))
        assertFalse(TotpBackup.isEncrypted(json))
        val parsed = TotpBackup.parse(json)
        assertEquals(sample, parsed)
    }

    @Test
    fun encryptedRoundTrip() {
        val password = "correct horse".toCharArray()
        val json = TotpBackup.toEncryptedJson(sample, password)
        assertTrue(TotpBackup.isEncrypted(json))
        assertFalse(json.contains("JBSWY3DPEHPK3PXP"))
        val parsed = TotpBackup.parse(json, "correct horse".toCharArray())
        assertEquals(sample, parsed)
    }

    @Test
    fun encryptedNeedsCorrectPassword() {
        val json = TotpBackup.toEncryptedJson(sample, "secret".toCharArray())
        try {
            TotpBackup.parse(json, "wrong".toCharArray())
            throw AssertionError("expected failure")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun parsesOtpauthLines() {
        val text = """
            otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example
            otpauth://totp/Bank:login?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&algorithm=SHA256
        """.trimIndent()
        val parsed = TotpBackup.parse(text)
        assertEquals(2, parsed.size)
        assertEquals("Example", parsed[0].issuer)
        assertEquals("alice@example.com", parsed[0].label)
        assertEquals(TotpAlgorithm.SHA256, parsed[1].algorithm)
    }
}
