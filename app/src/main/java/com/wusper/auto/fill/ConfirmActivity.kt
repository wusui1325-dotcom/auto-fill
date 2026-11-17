package com.wusper.auto.fill

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.wusper.auto.fill.databinding.ViewConfirmBinding

/**
 * Fallback half-screen prompt if the accessibility overlay cannot be shown.
 * Fill still goes through the accessibility service; this activity never writes the clipboard.
 */
class ConfirmActivity : AppCompatActivity() {
    private var handled = false
    private var unobserveSession: (() -> Unit)? = null
    private var unobserveUi: (() -> Unit)? = null
    private var binding: ViewConfirmBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableLockScreen()
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)

        val pending = OtpSession.pending
        if (pending == null || ConfirmPromptController.overlayAttached) {
            finish()
            return
        }

        val view = ViewConfirmBinding.inflate(layoutInflater)
        binding = view
        setContentView(view.root)
        view.confirmRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        bindPrompt(view, pending)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancel()
            }
        })
        unobserveSession = OtpSession.observe { snap ->
            onPromptState(snap)
        }
        unobserveUi = ConfirmPromptController.observeUi {
            if (ConfirmPromptController.overlayAttached && !handled) {
                finish()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false
        val pending = OtpSession.pending
        val view = binding
        if (pending == null || view == null || ConfirmPromptController.overlayAttached) {
            finish()
            return
        }
        bindPrompt(view, pending)
    }

    override fun onDestroy() {
        unobserveSession?.invoke()
        unobserveSession = null
        unobserveUi?.invoke()
        unobserveUi = null
        binding = null
        super.onDestroy()
    }

    private fun enableLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun onPromptState(snap: SessionSnapshot) {
        if (handled || isFinishing) return
        if (snap.pending == null || ConfirmPromptController.overlayAttached) {
            finish()
            return
        }
        binding?.let { bindPrompt(it, snap.pending) }
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
