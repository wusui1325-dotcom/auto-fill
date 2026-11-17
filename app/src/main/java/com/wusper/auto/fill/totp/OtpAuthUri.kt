package com.wusper.auto.fill.totp

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Google Authenticator-style otpauth://totp URIs. */
object OtpAuthUri {

    fun parse(uriString: String, id: String = UUID.randomUUID().toString()): TotpAccount {
        val uri = URI(uriString.trim())
        require(uri.scheme.equals("otpauth", ignoreCase = true)) { "Not an otpauth URI" }
        require(uri.host.equals("totp", ignoreCase = true)) { "Only totp is supported" }

        val path = uri.path?.trimStart('/') ?: ""
        val (issuerFromPath, label) = parseLabel(path)
        val params = parseQuery(uri.rawQuery)
        val secret = params["secret"] ?: throw IllegalArgumentException("Missing secret")
        Base32.decode(secret)
        val issuer = params["issuer"]?.takeIf { it.isNotBlank() } ?: issuerFromPath
        val algorithm = when (params["algorithm"]?.uppercase()) {
            "SHA256" -> TotpAlgorithm.SHA256
            "SHA512" -> TotpAlgorithm.SHA512
            else -> TotpAlgorithm.SHA1
        }
        return TotpAccount(
            id = id,
            issuer = issuer,
            label = label,
            secretBase32 = secret.replace(" ", "").replace("-", "").uppercase().trimEnd('='),
            algorithm = algorithm,
            digits = (params["digits"]?.toIntOrNull() ?: 6).coerceIn(6, 8),
            periodSeconds = (params["period"]?.toIntOrNull() ?: 30).coerceIn(15, 120)
        )
    }

    fun format(account: TotpAccount): String {
        val path = when {
            account.issuer.isNotBlank() && account.label.isNotBlank() ->
                "${encodePath(account.issuer)}:${encodePath(account.label)}"
            account.label.isNotBlank() -> encodePath(account.label)
            account.issuer.isNotBlank() -> encodePath(account.issuer)
            else -> "account"
        }
        val query = buildString {
            append("secret=").append(encodeQuery(account.secretBase32))
            if (account.issuer.isNotBlank()) append("&issuer=").append(encodeQuery(account.issuer))
            append("&digits=").append(account.digits)
            append("&period=").append(account.periodSeconds)
            if (account.algorithm != TotpAlgorithm.SHA1) {
                append("&algorithm=").append(account.algorithm.name)
            }
        }
        return "otpauth://totp/$path?$query"
    }

    private fun parseLabel(path: String): Pair<String, String> {
        if (path.isBlank()) return "" to ""
        val decoded = urlDecode(path)
        val colon = decoded.indexOf(':')
        return if (colon >= 0) {
            decoded.substring(0, colon).trim() to decoded.substring(colon + 1).trim()
        } else {
            "" to decoded.trim()
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else urlDecode(part.substring(0, eq)) to urlDecode(part.substring(eq + 1))
        }.toMap()
    }

    private fun urlDecode(s: String): String =
        URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name())

    private fun encodePath(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodeQuery(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name())
}
