package com.wusper.auto.fill

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wusper.auto.fill.databinding.ActivityAddTotpBinding
import com.wusper.auto.fill.totp.Base32
import com.wusper.auto.fill.totp.OtpAuthUri
import com.wusper.auto.fill.totp.TotpAccount
import com.wusper.auto.fill.totp.TotpAlgorithm
import com.wusper.auto.fill.totp.TotpGenerator
import com.wusper.auto.fill.totp.TotpVault
import java.util.UUID

class AddTotpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAddTotpBinding
    private lateinit var vault: TotpVault
    private val handler = Handler(Looper.getMainLooper())
    private var parsedFromUri = false
    private var algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    private var digits: Int = 6
    private var period: Int = 30

    private val previewTick = object : Runnable {
        override fun run() {
            if (isDestroyed || isFinishing) return
            updatePreview()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAddTotpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vault = TotpVault(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setTitle(R.string.totp_add_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s === binding.inputUri.text) onUriChanged()
                updatePreview()
            }
        }
        binding.inputUri.addTextChangedListener(watcher)
        binding.inputSecret.addTextChangedListener(watcher)

        val preset = intent.getStringExtra(EXTRA_URI)
        if (!preset.isNullOrBlank()) {
            binding.inputUri.setText(preset)
            onUriChanged()
            updatePreview()
        }
    }

    companion object {
        const val EXTRA_URI = "otpauth_uri"
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(previewTick)
        handler.post(previewTick)
    }

    override fun onPause() {
        handler.removeCallbacks(previewTick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun onUriChanged() {
        val raw = binding.inputUri.text?.toString()?.trim().orEmpty()
        if (!raw.startsWith("otpauth://", ignoreCase = true)) {
            if (parsedFromUri) {
                algorithm = TotpAlgorithm.SHA1
                digits = 6
                period = 30
                parsedFromUri = false
            }
            return
        }
        try {
            val account = OtpAuthUri.parse(raw)
            parsedFromUri = true
            algorithm = account.algorithm
            digits = account.digits
            period = account.periodSeconds
            if (binding.inputIssuer.text.isNullOrBlank()) binding.inputIssuer.setText(account.issuer)
            if (binding.inputLabel.text.isNullOrBlank()) binding.inputLabel.setText(account.label)
            if (binding.inputSecret.text.isNullOrBlank()) binding.inputSecret.setText(account.secretBase32)
        } catch (_: Exception) {
            parsedFromUri = false
        }
    }

    private fun updatePreview() {
        val secret = binding.inputSecret.text?.toString().orEmpty()
        if (!isValidSecret(secret)) {
            binding.preview.text = getString(R.string.totp_preview_invalid)
            return
        }
        val code = try {
            TotpGenerator.generate(
                secretBase32 = secret,
                periodSeconds = period,
                digits = digits,
                algorithm = algorithm
            )
        } catch (_: Exception) {
            binding.preview.text = getString(R.string.totp_preview_invalid)
            return
        }
        val remaining = TotpGenerator.remainingSeconds(periodSeconds = period)
        binding.preview.text = getString(
            R.string.totp_preview_ok,
            TotpActivity.formatDisplayCode(code),
            remaining
        )
    }

    private fun save() {
        val account = try {
            buildAccount()
        } catch (e: Exception) {
            val msg = when (e.message) {
                "secret" -> R.string.totp_secret_invalid
                "label" -> R.string.totp_name_required
                else -> R.string.totp_save_failed
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        try {
            TotpGenerator.generate(
                secretBase32 = account.secretBase32,
                periodSeconds = account.periodSeconds,
                digits = account.digits,
                algorithm = account.algorithm
            )
            vault.add(account)
            Toast.makeText(this, R.string.totp_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.totp_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildAccount(): TotpAccount {
        val uri = binding.inputUri.text?.toString()?.trim().orEmpty()
        if (uri.startsWith("otpauth://", ignoreCase = true)) {
            val parsed = OtpAuthUri.parse(uri)
            val issuer = binding.inputIssuer.text?.toString()?.trim().orEmpty().ifBlank { parsed.issuer }
            val label = binding.inputLabel.text?.toString()?.trim().orEmpty().ifBlank { parsed.label }
            val secret = binding.inputSecret.text?.toString()?.trim().orEmpty().ifBlank { parsed.secretBase32 }
            if (!isValidSecret(secret)) throw IllegalArgumentException("secret")
            return parsed.copy(
                id = UUID.randomUUID().toString(),
                issuer = issuer,
                label = label,
                secretBase32 = normalizeSecret(secret)
            )
        }
        val secret = binding.inputSecret.text?.toString()?.trim().orEmpty()
        if (!isValidSecret(secret)) throw IllegalArgumentException("secret")
        val issuer = binding.inputIssuer.text?.toString()?.trim().orEmpty()
        val label = binding.inputLabel.text?.toString()?.trim().orEmpty()
        if (issuer.isBlank() && label.isBlank()) {
            throw IllegalArgumentException("label")
        }
        return TotpAccount(
            id = UUID.randomUUID().toString(),
            issuer = issuer,
            label = label,
            secretBase32 = normalizeSecret(secret),
            algorithm = algorithm,
            digits = digits,
            periodSeconds = period
        )
    }

    private fun isValidSecret(secret: String): Boolean {
        return try {
            val decoded = Base32.decode(secret)
            decoded.size >= 10
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeSecret(secret: String): String {
        return secret.replace(" ", "").replace("-", "").uppercase().trimEnd('=')
    }
}
