package com.wusper.auto.fill.totp

/**
 * In-app TOTP account. The secret must only live in the encrypted vault.
 */
data class TotpAccount(
    val id: String,
    val issuer: String,
    val label: String,
    val secretBase32: String,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val digits: Int = 6,
    val periodSeconds: Int = 30
) {
    val displayName: String
        get() = when {
            issuer.isNotBlank() && label.isNotBlank() -> "$issuer ($label)"
            issuer.isNotBlank() -> issuer
            label.isNotBlank() -> label
            else -> "TOTP"
        }
}

enum class TotpAlgorithm {
    SHA1,
    SHA256,
    SHA512
}
