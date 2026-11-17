package com.wusper.auto.fill.totp

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * Google Authenticator export payloads (`otpauth-migration://offline?data=...`).
 * Decodes the documented protobuf subset offline; HOTP entries are skipped.
 */
data class MigrationBatch(
    val accounts: List<TotpAccount>,
    val version: Int = 1,
    val batchSize: Int = 1,
    val batchIndex: Int = 0
) {
    val isPartialBatch: Boolean get() = batchSize > 1
}

object OtpAuthMigration {

    fun isMigrationUri(text: String?): Boolean {
        return !extractUri(text).isNullOrBlank()
    }

    fun extractUri(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().removePrefix("\uFEFF")
        val match = URI_RE.find(text) ?: return null
        return match.value.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
    }

    fun parse(uriOrText: String): MigrationBatch {
        val uri = extractUri(uriOrText) ?: throw IllegalArgumentException("Not an otpauth-migration URI")
        val parsed = URI(uri)
        require(parsed.scheme.equals("otpauth-migration", ignoreCase = true)) {
            "Not an otpauth-migration URI"
        }
        val data = queryParams(parsed.rawQuery)["data"]
            ?: throw IllegalArgumentException("Missing data")
        val bytes = decodeData(data)
        return readPayload(bytes)
    }

    private fun queryParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())

    private fun decodeData(data: String): ByteArray {
        val cleaned = data.trim().replace(" ", "").replace("\n", "")
        val padded = when (cleaned.length % 4) {
            0 -> cleaned
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        val urlSafe = padded.replace('-', '+').replace('_', '/')
        return try {
            Base64.getDecoder().decode(urlSafe)
        } catch (_: Exception) {
            Base64.getUrlDecoder().decode(padded.replace('+', '-').replace('/', '_'))
        }
    }

    private fun readPayload(bytes: ByteArray): MigrationBatch {
        val reader = ProtoReader(bytes)
        val accounts = ArrayList<TotpAccount>()
        var version = 1
        var batchSize = 1
        var batchIndex = 0
        while (reader.hasMore()) {
            val (field, wire) = reader.readKey()
            when (field) {
                1 -> if (wire == 2) {
                    readParameters(reader.readBytes())?.let { accounts += it }
                } else reader.skip(wire)
                2 -> version = reader.readAsInt(wire)
                3 -> batchSize = reader.readAsInt(wire).coerceAtLeast(1)
                4 -> batchIndex = reader.readAsInt(wire).coerceAtLeast(0)
                else -> reader.skip(wire)
            }
        }
        return MigrationBatch(
            accounts = accounts,
            version = version,
            batchSize = batchSize,
            batchIndex = batchIndex
        )
    }

    private fun readParameters(bytes: ByteArray): TotpAccount? {
        val reader = ProtoReader(bytes)
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = TotpAlgorithm.SHA1
        var digits = 6
        var totp = true
        while (reader.hasMore()) {
            val (field, wire) = reader.readKey()
            when (field) {
                1 -> secret = if (wire == 2) reader.readBytes() else {
                    reader.skip(wire); secret
                }
                2 -> name = if (wire == 2) reader.readString() else {
                    reader.skip(wire); name
                }
                3 -> issuer = if (wire == 2) reader.readString() else {
                    reader.skip(wire); issuer
                }
                4 -> {
                    val algo = reader.readAsInt(wire)
                    algorithm = when (algo) {
                        2 -> TotpAlgorithm.SHA256
                        3 -> TotpAlgorithm.SHA512
                        4 -> return null // MD5 is not supported
                        else -> TotpAlgorithm.SHA1
                    }
                }
                5 -> {
                    val count = reader.readAsInt(wire)
                    digits = when (count) {
                        2 -> 8
                        else -> 6
                    }
                }
                6 -> {
                    val type = reader.readAsInt(wire)
                    if (type == 1) totp = false // HOTP
                }
                else -> reader.skip(wire)
            }
        }
        if (!totp) return null
        val rawSecret = secret ?: return null
        if (rawSecret.isEmpty()) return null
        val label = labelFrom(name, issuer)
        val issuerName = issuer.ifBlank { issuerFromName(name) }
        return TotpAccount(
            id = UUID.randomUUID().toString(),
            issuer = issuerName,
            label = label,
            secretBase32 = Base32.encode(rawSecret),
            algorithm = algorithm,
            digits = digits,
            periodSeconds = 30
        )
    }

    private fun labelFrom(name: String, issuer: String): String {
        val trimmed = name.trim()
        if (issuer.isNotBlank() && trimmed.startsWith("$issuer:", ignoreCase = true)) {
            return trimmed.substring(issuer.length + 1).trim()
        }
        val colon = trimmed.indexOf(':')
        return if (colon >= 0 && issuer.isBlank()) trimmed.substring(colon + 1).trim() else trimmed
    }

    private fun issuerFromName(name: String): String {
        val colon = name.indexOf(':')
        return if (colon > 0) name.substring(0, colon).trim() else ""
    }

    private val URI_RE = Regex(
        """otpauth-migration://[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )
}

internal class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun hasMore(): Boolean = pos < data.size

    fun readKey(): Pair<Int, Int> {
        val key = readVarint().toInt()
        return (key ushr 3) to (key and 0x07)
    }

    fun readAsInt(wire: Int): Int {
        return when (wire) {
            0 -> readVarint().toInt()
            else -> {
                skip(wire)
                0
            }
        }
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt().coerceAtLeast(0)
        require(pos + length <= data.size) { "Truncated protobuf bytes" }
        val out = data.copyOfRange(pos, pos + length)
        pos += length
        return out
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> {
                require(pos + 8 <= data.size) { "Truncated protobuf" }
                pos += 8
            }
            2 -> readBytes()
            5 -> {
                require(pos + 4 <= data.size) { "Truncated protobuf" }
                pos += 4
            }
            else -> throw IllegalArgumentException("Unknown protobuf wire type")
        }
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalArgumentException("Varint too long")
        }
        throw IllegalArgumentException("Truncated varint")
    }
}
