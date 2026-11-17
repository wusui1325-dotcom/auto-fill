package com.wusper.auto.fill

import android.os.Bundle
import android.view.Gravity
import androidx.activity.OnBackPressedCallback
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.wusper.auto.fill.databinding.ViewConfirmBinding

/**
 * Fallback half-screen prompt if the accessibility overlay cannot be shown.
 * Fill still goes through the accessibility service; this activity never writes the clipboard.
 */
class ConfirmActivity : AppCompatActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)

        val pending = OtpSession.pending
        if (pending == null) {
            finish()
            return
        }

        val binding = ViewConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.confirmRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        bindPrompt(binding, pending)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancel()
            }
        })
    }

    private fun bindPrompt(binding: ViewConfirmBinding, pending: PendingOtp) {
        binding.confirmCode.text = pending.code
        binding.confirmSource.text = getString(R.string.confirm_source, pending.source)
        binding.btnFill.setOnClickListener { accept() }
        binding.btnCancel.setOnClickListener { cancel() }
        binding.confirmRoot.setOnClickListener { cancel() }
        binding.confirmCard.setOnClickListener { /* swallow */ }
    }

    private fun accept() {
        if (handled) return
        handled = true
        // Dismiss this dialog first so the underlying webpage can regain focus;
        // the accessibility service fills after a short delay.
        val accepted = OtpSession.confirmFill()
        finish()
        if (!accepted) {
            Toast.makeText(this, R.string.fill_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancel() {
        if (handled) return
        handled = true
        OtpSession.cancelConfirm()
        finish()
    }

}
