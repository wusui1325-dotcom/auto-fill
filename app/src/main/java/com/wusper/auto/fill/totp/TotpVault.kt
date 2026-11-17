package com.wusper.auto.fill.totp

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted on-device store for TOTP accounts. Secrets never go to plaintext prefs.
 * Uses Android Keystore AES-GCM. If the key or ciphertext cannot be opened, access
 * fails rather than falling back to an unencrypted store.
 */
class TotpVault(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun listAccounts(): List<TotpAccount> = synchronized(lock) {
        val blob = prefs.getString(KEY_BLOB, null) ?: return emptyList()
        parseList(decrypt(blob))
    }

    fun add(account: TotpAccount): TotpAccount = synchronized(lock) {
        val stored = if (account.id.isBlank()) account.copy(id = UUID.randomUUID().toString()) else account
        val next = listLocked().toMutableList()
        val existing = next.indexOfFirst { sameIdentity(it, stored) }
        if (existing >= 0) {
            next[existing] = stored.copy(id = next[existing].id)
            saveLocked(next)
            return next[existing]
        }
        next += stored
        saveLocked(next)
        stored
    }

    fun addAll(accounts: List<TotpAccount>): Int = synchronized(lock) {
        if (accounts.isEmpty()) return 0
        val next = listLocked().toMutableList()
        var added = 0
        for (account in accounts) {
            val stored = if (account.id.isBlank()) account.copy(id = UUID.randomUUID().toString()) else account
            val existing = next.indexOfFirst { sameIdentity(it, stored) }
            if (existing >= 0) {
                next[existing] = stored.copy(id = next[existing].id)
            } else {
                next += stored
                added += 1
            }
        }
        saveLocked(next)
        added
    }

    fun delete(id: String) = synchronized(lock) {
        saveLocked(listLocked().filterNot { it.id == id })
    }

    fun get(id: String): TotpAccount? = listAccounts().firstOrNull { it.id == id }

    private fun listLocked(): List<TotpAccount> {
        val blob = prefs.getString(KEY_BLOB, null) ?: return emptyList()
        return parseList(decrypt(blob))
    }

    private fun saveLocked(accounts: List<TotpAccount>) {
        prefs.edit().putString(KEY_BLOB, encrypt(TotpBackup.toPlainJson(accounts))).commit()
    }

    private fun parseList(raw: String): List<TotpAccount> = TotpBackup.parse(raw)

    private fun sameIdentity(a: TotpAccount, b: TotpAccount): Boolean {
        if (a.id.isNotBlank() && a.id == b.id) return true
        return a.secretBase32.equals(b.secretBase32, ignoreCase = true) &&
            a.issuer.equals(b.issuer, ignoreCase = true) &&
            a.label.equals(b.label, ignoreCase = true)
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + ct.size)
        require(iv.size in 1..255)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    private fun decrypt(blob: String): String {
        val raw = java.util.Base64.getDecoder().decode(blob)
        require(raw.isNotEmpty()) { "Vault data is empty" }
        val ivLen = raw[0].toInt() and 0xFF
        require(ivLen in 1..32 && raw.size > 1 + ivLen) { "Vault data is invalid" }
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val ct = raw.copyOfRange(1 + ivLen, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "totp_vault"
        private const val KEY_BLOB = "blob"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "totp_vault_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
