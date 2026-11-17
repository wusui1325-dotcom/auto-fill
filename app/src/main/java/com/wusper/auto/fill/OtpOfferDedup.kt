package com.wusper.auto.fill

/**
 * Dedup keys so the same OTP is not offered twice — for example once from
 * a RECEIVE_SMS broadcast and again from the later notification.
 */
object OtpOfferDedup {
    const val WINDOW_MS = 2 * 60_000L

    fun keysFor(otp: PendingOtp): List<String> {
        val code = FillTargetHeuristics.compact(otp.code)
        return listOf(
            "${otp.packageName}|${otp.notificationKey}|${otp.code}",
            "code:$code"
        )
    }

    fun isDuplicate(
        recent: Iterable<Pair<String, Long>>,
        keys: Collection<String>,
        now: Long,
        windowMs: Long = WINDOW_MS
    ): Boolean {
        val want = keys.toSet()
        return recent.any { (key, at) -> key in want && now - at <= windowMs }
    }
}
