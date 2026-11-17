package com.wusper.auto.fill

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wusper.auto.fill.databinding.ActivityTotpBinding
import com.wusper.auto.fill.databinding.ItemTotpAccountBinding
import com.wusper.auto.fill.totp.TotpAccount
import com.wusper.auto.fill.totp.TotpBackup
import com.wusper.auto.fill.totp.TotpGenerator
import com.wusper.auto.fill.totp.TotpVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app TOTP authenticator. Opened from a button on the main screen,
 * kept separate from fill settings.
 */
class TotpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTotpBinding
    private lateinit var vault: TotpVault
    private val handler = Handler(Looper.getMainLooper())
    private val rows = LinkedHashMap<String, ItemTotpAccountBinding>()
    private var accounts: List<TotpAccount> = emptyList()
    private var pendingExportPassword: CharArray? = null
    private var pendingImportText: String? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null) {
            password?.fill(0.toChar())
            return@registerForActivityResult
        }
        exportTo(uri, password)
    }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) startImport(uri)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (isDestroyed || isFinishing) return
            refreshCodes()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTotpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vault = TotpVault(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setTitle(R.string.totp_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnAdd.setOnClickListener {
            startActivity(Intent(this, AddTotpActivity::class.java))
        }
        binding.btnImport.setOnClickListener {
            openDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.btnExport.setOnClickListener { askExportPassword() }
    }

    override fun onResume() {
        super.onResume()
        reload()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pendingExportPassword?.fill(0.toChar())
        pendingExportPassword = null
        super.onDestroy()
    }

    private fun reload() {
        accounts = try {
            vault.listAccounts()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.totp_vault_error, Toast.LENGTH_LONG).show()
            emptyList()
        }
        renderList()
        refreshCodes()
    }

    private fun renderList() {
        val ids = accounts.map { it.id }
        if (ids != rows.keys.toList()) {
            binding.accounts.removeAllViews()
            rows.clear()
            val inflater = LayoutInflater.from(this)
            for (account in accounts) {
                val row = ItemTotpAccountBinding.inflate(inflater, binding.accounts, false)
                row.accountName.text = account.displayName
                row.btnFill.setOnClickListener { offerFill(account) }
                row.btnDelete.setOnClickListener { confirmDelete(account) }
                row.accountCode.setOnLongClickListener {
                    copyCode(account)
                    true
                }
                binding.accounts.addView(row.root)
                rows[account.id] = row
            }
        } else {
            for (account in accounts) {
                rows[account.id]?.accountName?.text = account.displayName
            }
        }
        binding.empty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        binding.btnExport.isEnabled = accounts.isNotEmpty()
    }

    private fun refreshCodes() {
        val now = System.currentTimeMillis()
        for (account in accounts) {
            val row = rows[account.id] ?: continue
            val code = try {
                TotpGenerator.generate(
                    secretBase32 = account.secretBase32,
                    timeMillis = now,
                    periodSeconds = account.periodSeconds,
                    digits = account.digits,
                    algorithm = account.algorithm
                )
            } catch (_: Exception) {
                "------"
            }
            row.accountCode.text = formatDisplayCode(code)
            val remaining = TotpGenerator.remainingSeconds(now, account.periodSeconds)
            row.accountRemaining.text = getString(R.string.totp_remaining, remaining)
            val period = account.periodSeconds.coerceIn(15, 120)
            row.accountProgress.max = period * 100
            row.accountProgress.progress = remaining * 100
        }
    }

    private fun offerFill(account: TotpAccount) {
        val code = try {
            TotpGenerator.generate(
                secretBase32 = account.secretBase32,
                periodSeconds = account.periodSeconds,
                digits = account.digits,
                algorithm = account.algorithm
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.totp_generate_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!OtpSession.accessibilityConnected) {
            Toast.makeText(this, R.string.totp_need_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        val offered = OtpSession.offerManualFill(
            PendingOtp(
                code = code,
                source = account.displayName,
                packageName = packageName,
                notificationKey = "totp:${account.id}:${code}",
                postedAt = System.currentTimeMillis()
            )
        )
        if (!offered) {
            Toast.makeText(this, R.string.totp_offer_failed, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.totp_confirm_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyCode(account: TotpAccount) {
        val code = try {
            TotpGenerator.generate(
                secretBase32 = account.secretBase32,
                periodSeconds = account.periodSeconds,
                digits = account.digits,
                algorithm = account.algorithm
            )
        } catch (_: Exception) {
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.totp_title), code))
        Toast.makeText(this, R.string.totp_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(account: TotpAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.totp_delete_title)
            .setMessage(getString(R.string.totp_delete_body, account.displayName))
            .setNegativeButton(R.string.confirm_cancel, null)
            .setPositiveButton(R.string.totp_delete) { _, _ ->
                try {
                    vault.delete(account.id)
                    reload()
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.totp_vault_error, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun askExportPassword() {
        val field = passwordField()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.totp_export_password_title)
            .setMessage(R.string.totp_export_password_body)
            .setView(field.root)
            .setNegativeButton(R.string.confirm_cancel, null)
            .setPositiveButton(R.string.totp_export) { _, _ ->
                val text = field.edit.text
                pendingExportPassword = if (text.isNullOrEmpty()) null else CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                createDocument.launch("autofill-totp-$stamp.json")
            }
            .show()
    }

    private fun exportTo(uri: Uri, password: CharArray?) {
        try {
            val list = vault.listAccounts()
            val body = if (password != null && password.isNotEmpty()) {
                TotpBackup.toEncryptedJson(list, password)
            } else {
                TotpBackup.toPlainJson(list)
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("stream")
            val msg = if (password != null && password.isNotEmpty()) {
                R.string.totp_export_encrypted_ok
            } else {
                R.string.totp_export_plain_ok
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.totp_export_failed, Toast.LENGTH_LONG).show()
        } finally {
            password?.fill(0.toChar())
        }
    }

    private fun startImport(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.totp_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (text.length > 1_000_000) {
            Toast.makeText(this, R.string.totp_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (TotpBackup.isEncrypted(text)) {
            pendingImportText = text
            val field = passwordField()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.totp_import_password_title)
                .setMessage(R.string.totp_import_password_body)
                .setView(field.root)
                .setNegativeButton(R.string.confirm_cancel) { _, _ -> pendingImportText = null }
                .setPositiveButton(R.string.totp_import) { _, _ ->
                    val raw = field.edit.text
                    val password = if (raw.isNullOrEmpty()) CharArray(0) else CharArray(raw.length).also { raw.getChars(0, raw.length, it, 0) }
                    finishImport(pendingImportText, password)
                    pendingImportText = null
                }
                .show()
        } else {
            finishImport(text, null)
        }
    }

    private fun finishImport(text: String?, password: CharArray?) {
        try {
            if (text.isNullOrBlank()) throw IllegalArgumentException("empty")
            val parsed = TotpBackup.parse(text, password)
            if (parsed.isEmpty()) {
                Toast.makeText(this, R.string.totp_import_empty, Toast.LENGTH_LONG).show()
                return
            }
            val added = vault.addAll(parsed)
            reload()
            Toast.makeText(this, getString(R.string.totp_import_ok, added, parsed.size), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.totp_import_failed, Toast.LENGTH_LONG).show()
        } finally {
            password?.fill(0.toChar())
        }
    }

    private fun passwordField(): PasswordField {
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.totp_password_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(edit)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        return PasswordField(wrap, edit)
    }

    private class PasswordField(val root: View, val edit: TextInputEditText)

    companion object {
        fun formatDisplayCode(code: String): String {
            return when (code.length) {
                6 -> code.substring(0, 3) + " " + code.substring(3)
                8 -> code.substring(0, 4) + " " + code.substring(4)
                else -> code
            }
        }
    }
}
