package com.wusper.auto.fill.totp

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Import/export for in-app TOTP accounts. No network.
 * Plain JSON, lines of otpauth URIs, or a password-wrapped vault file.
 */
object TotpBackup {
    const val FORMAT_VERSION = 1
    private const val PBKDF_ITERATIONS = 100_000
    private const val GCM_TAG_BITS = 128

    fun toPlainJson(accounts: List<TotpAccount>): String {
        val items = accounts.joinToString(",\n    ") { toJson(it) }
        return buildString {
            append("{\n")
            append("  \"format\": \"autofill-totp\",\n")
            append("  \"version\": ").append(FORMAT_VERSION).append(",\n")
            append("  \"accounts\": [")
            if (accounts.isNotEmpty()) {
                append("\n    ").append(items).append("\n  ")
            }
            append("]\n}")
        }
    }

    fun toEncryptedJson(accounts: List<TotpAccount>, password: CharArray): String {
        require(password.isNotEmpty()) { "Password required" }
        val plain = toPlainJson(accounts).toByteArray(Charsets.UTF_8)
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val ct = cipher.doFinal(plain)
            buildString {
                append("{\n")
                append("  \"format\": \"autofill-totp-encrypted\",\n")
                append("  \"version\": ").append(FORMAT_VERSION).append(",\n")
                append("  \"kdf\": \"PBKDF2WithHmacSHA256\",\n")
                append("  \"iter\": ").append(PBKDF_ITERATIONS).append(",\n")
                append("  \"salt\": \"").append(b64(salt)).append("\",\n")
                append("  \"iv\": \"").append(b64(iv)).append("\",\n")
                append("  \"ct\": \"").append(b64(ct)).append("\"\n")
                append("}")
            }
        } finally {
            key.fill(0)
        }
    }

    fun parse(text: String, password: CharArray? = null): List<TotpAccount> {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{")) {
            val fields = objectFields(trimmed)
            val format = fields["format"] ?: ""
            if (format == "autofill-totp-encrypted") {
                val pw = password ?: throw IllegalArgumentException("Password required")
                return parse(decrypt(fields, pw))
            }
            val accountsRaw = arraySection(trimmed, "accounts")
            if (format == "autofill-totp" || accountsRaw != null) {
                return parseAccountObjects(accountsRaw ?: "[]")
            }
        }
        val found = ArrayList<TotpAccount>()
        for (line in trimmed.split('\n', '\r', ' ', '\t')) {
            val part = line.trim()
            if (part.startsWith("otpauth://", ignoreCase = true)) {
                found += OtpAuthUri.parse(part)
            }
        }
        if (found.isEmpty() && trimmed.startsWith("otpauth://", ignoreCase = true)) {
            found += OtpAuthUri.parse(trimmed)
        }
        return found
    }

    fun isEncrypted(text: String): Boolean {
        if (!text.trim().startsWith("{")) return false
        return try {
            objectFields(text.trim().removePrefix("\uFEFF"))["format"] == "autofill-totp-encrypted"
        } catch (_: Exception) {
            false
        }
    }

    internal fun toJson(account: TotpAccount): String {
        return buildString {
            append("{")
            append("\"id\":").append(quote(account.id)).append(",")
            append("\"issuer\":").append(quote(account.issuer)).append(",")
            append("\"label\":").append(quote(account.label)).append(",")
            append("\"secret\":").append(quote(account.secretBase32)).append(",")
            append("\"algorithm\":").append(quote(account.algorithm.name)).append(",")
            append("\"digits\":").append(account.digits).append(",")
            append("\"period\":").append(account.periodSeconds)
            append("}")
        }
    }

    private fun parseAccountObjects(arrayBody: String): List<TotpAccount> {
        val objects = topLevelObjects(arrayBody)
        val out = ArrayList<TotpAccount>(objects.size)
        for (raw in objects) {
            val obj = objectFields(raw)
            val secret = (obj["secret"] ?: obj["secretBase32"] ?: "").trim()
            if (secret.isBlank()) continue
            val algorithm = when (obj["algorithm"]?.uppercase()) {
                "SHA256" -> TotpAlgorithm.SHA256
                "SHA512" -> TotpAlgorithm.SHA512
                else -> TotpAlgorithm.SHA1
            }
            val period = obj["period"]?.toIntOrNull()
                ?: obj["periodSeconds"]?.toIntOrNull()
                ?: 30
            out += TotpAccount(
                id = obj["id"]?.ifBlank { null } ?: UUID.randomUUID().toString(),
                issuer = obj["issuer"] ?: "",
                label = obj["label"] ?: "",
                secretBase32 = secret.replace(" ", "").replace("-", "").uppercase().trimEnd('='),
                algorithm = algorithm,
                digits = (obj["digits"]?.toIntOrNull() ?: 6).coerceIn(6, 8),
                periodSeconds = period.coerceIn(15, 120)
            )
        }
        return out
    }

    private fun decrypt(fields: Map<String, String>, password: CharArray): String {
        val salt = unb64(fields["salt"] ?: throw IllegalArgumentException("Missing salt"))
        val iv = unb64(fields["iv"] ?: throw IllegalArgumentException("Missing iv"))
        val ct = unb64(fields["ct"] ?: throw IllegalArgumentException("Missing ct"))
        val iter = fields["iter"]?.toIntOrNull()?.coerceAtLeast(10_000) ?: PBKDF_ITERATIONS
        val key = deriveKey(password, salt, iter)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iter: Int = PBKDF_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iter, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        SecureRandom().nextBytes(out)
        return out
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Reads top-level string/number fields of a JSON object. Nested objects/arrays
     * are skipped; use [arraySection] for a named array.
     */
    private fun objectFields(json: String): Map<String, String> {
        val body = unwrapObject(json)
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.length) {
            i = skipWs(body, i)
            if (i >= body.length) break
            if (body[i] == ',') {
                i++
                continue
            }
            val keyPair = readString(body, i) ?: break
            i = skipWs(body, keyPair.second)
            if (i >= body.length || body[i] != ':') break
            i = skipWs(body, i + 1)
            if (i >= body.length) break
            when (body[i]) {
                '"' -> {
                    val value = readString(body, i) ?: break
                    out[keyPair.first] = value.first
                    i = value.second
                }
                '{', '[' -> {
                    i = skipValue(body, i)
                }
                else -> {
                    val start = i
                    while (i < body.length && body[i] !in ",}") i++
                    out[keyPair.first] = body.substring(start, i).trim()
                }
            }
        }
        return out
    }

    private fun arraySection(json: String, key: String): String? {
        val body = unwrapObject(json)
        var i = 0
        while (i < body.length) {
            i = skipWs(body, i)
            if (i >= body.length) return null
            if (body[i] == ',') {
                i++
                continue
            }
            val keyPair = readString(body, i) ?: return null
            i = skipWs(body, keyPair.second)
            if (i >= body.length || body[i] != ':') return null
            i = skipWs(body, i + 1)
            if (keyPair.first == key && i < body.length && body[i] == '[') {
                val end = skipValue(body, i)
                return body.substring(i, end)
            }
            i = skipValue(body, i)
        }
        return null
    }

    private fun topLevelObjects(arrayJson: String): List<String> {
        val body = arrayJson.trim()
        val inner = if (body.startsWith("[")) body.substring(1, body.length - 1) else body
        val out = ArrayList<String>()
        var i = 0
        while (i < inner.length) {
            i = skipWs(inner, i)
            if (i >= inner.length) break
            if (inner[i] == ',') {
                i++
                continue
            }
            if (inner[i] != '{') break
            val end = skipValue(inner, i)
            out += inner.substring(i, end)
            i = end
        }
        return out
    }

    private fun unwrapObject(json: String): String {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return trimmed
        return trimmed.substring(1, trimmed.length - 1)
    }

    private fun readString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') return sb.toString() to (i + 1)
            if (ch == '\\' && i + 1 < s.length) {
                i++
                when (val esc = s[i]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) return null
                        sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return null
    }

    private fun skipValue(s: String, start: Int): Int {
        var i = skipWs(s, start)
        if (i >= s.length) return i
        return when (s[i]) {
            '"' -> readString(s, i)?.second ?: s.length
            '{' -> skipBalanced(s, i, '{', '}')
            '[' -> skipBalanced(s, i, '[', ']')
            else -> {
                while (i < s.length && s[i] !in ",}]") i++
                i
            }
        }
    }

    private fun skipBalanced(s: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        var inString = false
        var escape = false
        while (i < s.length) {
            val ch = s[i]
            if (inString) {
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return s.length
    }

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }
}
