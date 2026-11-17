package com.wusper.auto.fill.totp

/** RFC 4648 Base32 (padding optional on decode). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val cleaned = input.trim()
            .replace(" ", "")
            .replace("-", "")
            .uppercase()
            .trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>((cleaned.length * 5) / 8)
        for (ch in cleaned) {
            val idx = ALPHABET.indexOf(ch)
            require(idx >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }
}
