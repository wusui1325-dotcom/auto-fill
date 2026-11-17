package com.wusper.auto.fill

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Single owner of the confirm overlay view.
 * Tries the accessibility overlay first when the service is the host and keeps it if it attaches;
 * TYPE_APPLICATION_OVERLAY is only the fallback.
 */
object ConfirmOverlayPresenter {
    private val main = Handler(Looper.getMainLooper())
    private val window = ConfirmOverlayWindow()
    private var host: Context? = null
    private var types: List<OverlayWindowKind> = emptyList()
    private var typeIndex = 0
    private var attempts = 0
    private var shownKey: String? = null
    private var fallbackKind: OverlayWindowKind? = null
    private var upgrading = false

    private val retryAttach = Runnable { attachCurrent() }

    fun show(host: Context, pending: PendingOtp) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(host, pending) }
            return
        }
        val canDraw = Settings.canDrawOverlays(host)
        val a11yHost = host is AccessibilityService
        val planned = OverlayWindowPolicy.typesToTry(
            accessibilityConnected = a11yHost,
            canDrawOverlays = canDraw
        )
        if (planned.isEmpty()) {
            ConfirmPromptController.reportOverlayFailed(host)
            return
        }
        this.host = host
        window.bind(host, pending)
        val key = pendingKey(pending)
        if (window.isVisiblyAttached() && shownKey == key && !shouldUpgrade()) {
            ConfirmPromptController.reportOverlayAttached(true)
            return
        }
        types = planned
        typeIndex = 0
        attempts = 0
        fallbackKind = null
        upgrading = false
        main.removeCallbacks(retryAttach)
        attachCurrent()
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { hide() }
            return
        }
        main.removeCallbacks(retryAttach)
        shownKey = null
        typeIndex = 0
        attempts = 0
        fallbackKind = null
        upgrading = false
        window.detach()
    }

    fun release() {
        hide()
        window.release()
        host = null
        types = emptyList()
    }

    private fun attachCurrent() {
        val pending = OtpSession.pending ?: return
        val host = this.host ?: return
        val kind = types.getOrNull(typeIndex)
        if (kind == null) {
            val fallback = fallbackKind
            if (fallback != null) {
                typeIndex = types.indexOf(fallback)
                fallbackKind = null
                if (typeIndex >= 0 && window.attach(host, fallback) && window.isVisiblyAttached()) {
                    shownKey = pendingKey(pending)
                    ConfirmPromptController.reportOverlayAttached(true)
                    return
                }
            }
            ConfirmPromptController.reportOverlayFailed(host)
            return
        }
        window.bind(host, pending)
        if (!window.attach(host, kind)) {
            scheduleRetryOrNext()
            return
        }
        // addView already succeeded; do not wait for a later isShown check
        // or the 700–2500 ms fallback will open the in-app screen instead.
        shownKey = pendingKey(pending)
        ConfirmPromptController.reportOverlayAttached(true)
        main.post { verify(pending) }
    }

    private fun verify(pending: PendingOtp) {
        if (OtpSession.pending?.let { pendingKey(it) } != pendingKey(pending)) return
        val host = this.host ?: return
        if (window.isVisiblyAttached()) {
            if (shouldUpgrade() && !upgrading) {
                upgradeToApplication(pending, host)
                return
            }
            shownKey = pendingKey(pending)
            ConfirmPromptController.reportOverlayAttached(true)
            return
        }
        window.forceVisible()
        if (window.isVisiblyAttached()) {
            if (shouldUpgrade() && !upgrading) {
                upgradeToApplication(pending, host)
                return
            }
            shownKey = pendingKey(pending)
            ConfirmPromptController.reportOverlayAttached(true)
            return
        }
        scheduleRetryOrNext()
    }

    private fun shouldUpgrade(): Boolean {
        val current = types.getOrNull(typeIndex)
        val remaining = types.drop(typeIndex + 1)
        return OverlayWindowPolicy.shouldUpgradeToApplication(current, remaining)
    }

    private fun upgradeToApplication(pending: PendingOtp, host: Context) {
        upgrading = true
        fallbackKind = OverlayWindowKind.ACCESSIBILITY
        window.detach()
        typeIndex = types.indexOf(OverlayWindowKind.APPLICATION)
        attempts = 0
        if (typeIndex < 0) {
            upgrading = false
            typeIndex = types.indexOf(OverlayWindowKind.ACCESSIBILITY)
            attachCurrent()
            return
        }
        window.bind(host, pending)
        if (!window.attach(host, OverlayWindowKind.APPLICATION)) {
            upgrading = false
            scheduleRetryOrNext()
            return
        }
        main.post {
            upgrading = false
            if (window.isVisiblyAttached()) {
                fallbackKind = null
                shownKey = pendingKey(pending)
                ConfirmPromptController.reportOverlayAttached(true)
            } else {
                scheduleRetryOrNext()
            }
        }
    }

    private fun scheduleRetryOrNext() {
        attempts++
        if (attempts < ConfirmPromptPolicy.OVERLAY_RETRY_LIMIT) {
            val delay = ConfirmPromptPolicy.OVERLAY_RETRY_DELAY_MS * attempts
            main.removeCallbacks(retryAttach)
            main.postDelayed(retryAttach, delay)
            return
        }
        attempts = 0
        typeIndex++
        main.removeCallbacks(retryAttach)
        main.post(retryAttach)
    }

    private fun pendingKey(pending: PendingOtp): String {
        return "${pending.packageName}|${pending.notificationKey}|${pending.code}"
    }
}
