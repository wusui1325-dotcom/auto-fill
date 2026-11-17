package com.wusper.auto.fill.totp

/**
 * Import accounts from a backup file, otpauth URIs, or a Google Authenticator
 * export QR payload (`otpauth-migration://`).
 */
object TotpImport {
    data class Result(
        val accounts: List<TotpAccount>,
        val batch: MigrationBatch? = null
    )

    fun parse(text: String, password: CharArray? = null): Result {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return Result(emptyList())
        if (TotpBackup.isEncrypted(trimmed) || looksLikeBackupJson(trimmed)) {
            return Result(TotpBackup.parse(trimmed, password))
        }
        val accounts = LinkedHashMap<String, TotpAccount>()
        var batch: MigrationBatch? = null
        val migrationUri = OtpAuthMigration.extractUri(trimmed)
        if (migrationUri != null) {
            batch = OtpAuthMigration.parse(trimmed)
            for (account in batch.accounts) accounts.putIfAbsent(identity(account), account)
        }
        for (account in TotpBackup.parse(trimmed, password)) {
            accounts.putIfAbsent(identity(account), account)
        }
        return Result(accounts.values.toList(), batch)
    }

    private fun looksLikeBackupJson(text: String): Boolean {
        if (!text.startsWith("{")) return false
        return text.contains("\"accounts\"") || text.contains("autofill-totp")
    }

    private fun identity(account: TotpAccount): String {
        return listOf(
            account.secretBase32.uppercase(),
            account.issuer.lowercase(),
            account.label.lowercase()
        ).joinToString("|")
    }
}
