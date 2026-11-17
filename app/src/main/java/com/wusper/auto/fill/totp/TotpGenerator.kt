package com.wusper.auto.fill.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/** RFC 6238 TOTP. Default: 6 digits, SHA1, 30-second steps. Offline only. */
object TotpGenerator {

    fun generate(
        secretBase32: String,
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    ): String {
        val period = periodSeconds.coerceIn(15, 120)
        val length = digits.coerceIn(6, 8)
        val counter = timeMillis / 1000L / period
        return generateHotp(Base32.decode(secretBase32), counter, length, algorithm)
    }

    fun remainingSeconds(
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30
    ): Int {
        val period = periodSeconds.coerceIn(15, 120)
        val elapsed = (timeMillis / 1000L) % period
        return (period - elapsed).toInt().coerceIn(1, period)
    }

    private fun generateHotp(
        key: ByteArray,
        counter: Long,
        digits: Int,
        algorithm: TotpAlgorithm
    ): String {
        val msg = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            msg[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val mac = Mac.getInstance(hmacAlgo(algorithm))
        mac.init(SecretKeySpec(key, mac.algorithm))
        val hash = mac.doFinal(msg)
        val offset = hash.last().toInt() and 0x0F
        val binary =
            ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        val otp = binary % (10.0.pow(digits).toInt())
        return otp.toString().padStart(digits, '0')
    }

    private fun hmacAlgo(algorithm: TotpAlgorithm): String = when (algorithm) {
        TotpAlgorithm.SHA1 -> "HmacSHA1"
        TotpAlgorithm.SHA256 -> "HmacSHA256"
        TotpAlgorithm.SHA512 -> "HmacSHA512"
    }
}
