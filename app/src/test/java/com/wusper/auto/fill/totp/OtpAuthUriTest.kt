package com.wusper.auto.fill.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpAuthUriTest {

    @Test
    fun parsesStandardUri() {
        val account = OtpAuthUri.parse(
            "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        )
        assertEquals("Example", account.issuer)
        assertEquals("alice@example.com", account.label)
        assertEquals("JBSWY3DPEHPK3PXP", account.secretBase32)
        assertEquals(TotpAlgorithm.SHA1, account.algorithm)
        assertEquals(6, account.digits)
        assertEquals(30, account.periodSeconds)
    }

    @Test
    fun parsesSha256AndCustomPeriod() {
        val account = OtpAuthUri.parse(
            "otpauth://totp/GitHub:octocat?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&algorithm=SHA256&digits=8&period=60"
        )
        assertEquals(TotpAlgorithm.SHA256, account.algorithm)
        assertEquals(8, account.digits)
        assertEquals(60, account.periodSeconds)
        assertEquals("GitHub", account.issuer)
        assertEquals("octocat", account.label)
    }

    @Test
    fun formatThenParseRoundTrip() {
        val original = TotpAccount(
            id = "id-1",
            issuer = "商店",
            label = "user@example.com",
            secretBase32 = "JBSWY3DPEHPK3PXP",
            algorithm = TotpAlgorithm.SHA512,
            digits = 8,
            periodSeconds = 45
        )
        val parsed = OtpAuthUri.parse(OtpAuthUri.format(original), id = "id-1")
        assertEquals(original, parsed)
    }

    @Test
    fun rejectsHotp() {
        try {
            OtpAuthUri.parse("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP")
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("totp"))
        }
    }
}
