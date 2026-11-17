package com.wusper.auto.fill.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpGeneratorTest {
    // RFC 6238 Appendix B, SHA1 secret "12345678901234567890"
    private val sha1 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val sha256 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA===="
    private val sha512 =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA="

    @Test
    fun rfc6238Sha1EightDigits() {
        assertEquals("94287082", code(sha1, 59_000L, 8, TotpAlgorithm.SHA1))
        assertEquals("07081804", code(sha1, 1_111_111_109_000L, 8, TotpAlgorithm.SHA1))
        assertEquals("14050471", code(sha1, 1_111_111_111_000L, 8, TotpAlgorithm.SHA1))
        assertEquals("89005924", code(sha1, 1_234_567_890_000L, 8, TotpAlgorithm.SHA1))
        assertEquals("69279037", code(sha1, 2_000_000_000_000L, 8, TotpAlgorithm.SHA1))
    }

    @Test
    fun defaultSixDigitSha1MatchesTruncatedRfcVector() {
        assertEquals("287082", code(sha1, 59_000L, 6, TotpAlgorithm.SHA1))
    }

    @Test
    fun rfc6238Sha256AndSha512() {
        assertEquals("46119246", code(sha256, 59_000L, 8, TotpAlgorithm.SHA256))
        assertEquals("90693936", code(sha512, 59_000L, 8, TotpAlgorithm.SHA512))
    }

    @Test
    fun remainingSecondsNeverZeroInsidePeriod() {
        assertEquals(1, TotpGenerator.remainingSeconds(59_000L, 30))
        assertEquals(30, TotpGenerator.remainingSeconds(60_000L, 30))
        val left = TotpGenerator.remainingSeconds(1_000L, 30)
        assertTrue(left in 1..30)
    }

    @Test
    fun base32RoundTrip() {
        val raw = "12345678901234567890".toByteArray()
        val encoded = Base32.encode(raw)
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", encoded)
        assertTrue(Base32.decode(encoded + "====").contentEquals(raw))
        assertTrue(Base32.decode("gez dg-nbvgy3tqojqgezdgnbvgy3tqojq").contentEquals(raw))
    }

    private fun code(
        secret: String,
        timeMillis: Long,
        digits: Int,
        algorithm: TotpAlgorithm
    ): String {
        return TotpGenerator.generate(secret, timeMillis, 30, digits, algorithm)
    }
}
