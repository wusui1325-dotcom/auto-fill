package com.wusper.auto.fill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpOfferDedupTest {

    private fun otp(code: String, pkg: String, key: String) = PendingOtp(
        code = code,
        source = "SMS",
        packageName = pkg,
        notificationKey = key,
        postedAt = 1L
    )

    @Test
    fun sameCodeFromSmsAndLaterNotificationIsDuplicate() {
        val sms = otp("123456", "sms", "sms:+1555:123456")
        val notif = otp("123456", "com.google.android.apps.messaging", "0|123")
        val keys = OtpOfferDedup.keysFor(sms)
        val recent = keys.map { it to 1_000L }
        assertTrue(
            OtpOfferDedup.isDuplicate(recent, OtpOfferDedup.keysFor(notif), now = 2_000L)
        )
    }

    @Test
    fun differentCodesAreNotDuplicates() {
        val first = OtpOfferDedup.keysFor(otp("111111", "sms", "a"))
        val recent = first.map { it to 1_000L }
        assertFalse(
            OtpOfferDedup.isDuplicate(
                recent,
                OtpOfferDedup.keysFor(otp("222222", "sms", "b")),
                now = 2_000L
            )
        )
    }

    @Test
    fun exactSourceKeyStillDedups() {
        val otp = otp("123456", "pkg", "k1")
        val keys = OtpOfferDedup.keysFor(otp)
        val recent = keys.map { it to 1_000L }
        assertTrue(OtpOfferDedup.isDuplicate(recent, keys, now = 1_500L))
    }

    @Test
    fun expiredEntriesAreNotDuplicates() {
        val sms = OtpOfferDedup.keysFor(otp("123456", "sms", "a"))
        val recent = sms.map { it to 1_000L }
        assertFalse(
            OtpOfferDedup.isDuplicate(
                recent,
                OtpOfferDedup.keysFor(otp("123456", "pkg", "b")),
                now = 1_000L + OtpOfferDedup.WINDOW_MS + 1
            )
        )
    }
}
