package com.wusper.auto.fill.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpAuthMigrationTest {

    // Built from the published Google Authenticator protobuf layout.
    private val multi =
        "otpauth-migration://offline?data=Ci4KCkhlbGxvId6tvu8SEWFsaWNlQGV4YW1wbGUuY29tGgdFeGFtcGxlIAEoATACCi4KFDEyMzQ1Njc4OTAxMjM0NTY3ODkwEgpCYW5rOmxvZ2luGgRCYW5rIAIoAjACCiEKCkhlbGxvId6tvu8SCWhvdHAtdXNlchoGU2tpcE1lMAEQARgCIAA"
    private val single =
        "otpauth-migration://offline?data=Ci4KCkhlbGxvId6tvu8SEWFsaWNlQGV4YW1wbGUuY29tGgdFeGFtcGxlIAEoATACEAE"

    @Test
    fun extractsMigrationUri() {
        assertEquals(
            single,
            OtpAuthMigration.extractUri("Export\n$single\n")
        )
        assertTrue(OtpAuthMigration.isMigrationUri(multi))
        assertNull(OtpAuthMigration.extractUri("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun parsesSingleTotpAccount() {
        val batch = OtpAuthMigration.parse(single)
        assertEquals(1, batch.accounts.size)
        assertFalse(batch.isPartialBatch)
        val account = batch.accounts[0]
        assertEquals("Example", account.issuer)
        assertEquals("alice@example.com", account.label)
        assertEquals("JBSWY3DPEHPK3PXP", account.secretBase32)
        assertEquals(TotpAlgorithm.SHA1, account.algorithm)
        assertEquals(6, account.digits)
        assertEquals(30, account.periodSeconds)
        assertEquals(
            "996554",
            TotpGenerator.generate(account.secretBase32, 59_000L, 30, 6, TotpAlgorithm.SHA1)
        )
    }

    @Test
    fun parsesBatchAndSkipsHotp() {
        val batch = OtpAuthMigration.parse(multi)
        assertEquals(2, batch.accounts.size)
        assertTrue(batch.isPartialBatch)
        assertEquals(2, batch.batchSize)
        assertEquals(0, batch.batchIndex)
        val first = batch.accounts[0]
        val second = batch.accounts[1]
        assertEquals("Example", first.issuer)
        assertEquals("alice@example.com", first.label)
        assertEquals("Bank", second.issuer)
        assertEquals("login", second.label)
        assertEquals(TotpAlgorithm.SHA256, second.algorithm)
        assertEquals(8, second.digits)
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", second.secretBase32)
        assertTrue(batch.accounts.none { it.issuer == "SkipMe" })
    }
}
