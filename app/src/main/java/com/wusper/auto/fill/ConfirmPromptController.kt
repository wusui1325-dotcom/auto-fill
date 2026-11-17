package com.wusper.auto.fill

import android.content.Context
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Coordinates the confirm overlay and the notification / activity fallback
 * so only one prompt is visible. Lives in the app process and still works
 * when the accessibility service is not connected.
 */
object ConfirmPromptController {
    @Volatile
    var overlayAttached: Boolean = false
        private set

    @Volatile
    var lastDeliveryFailed: Boolean = false
        private set

    @Volatile
    var fallbackAttempted: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    private var started = false
    private var overlayRetriesExhausted = false
    private var fallbackKey: String? = null
    private var unobserve: (() -> Unit)? = null

    private val waitForOverlay = Runnable {
        val snap = OtpSession.snapshot()
        if (snap.pending != null && !overlayAttached) {
            overlayRetriesExhausted = true
            apply(snap, app)
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        ConfirmNotifier.ensureChannel(app!!)
        unobserve = OtpSession.observe { apply(it, null) }
    }

    fun observeUi(listener: () -> Unit): () -> Unit {
        uiListeners.add(listener)
        return { uiListeners.remove(listener) }
    }

    fun reportOverlayAttached(attached: Boolean) {
        val changed = overlayAttached != attached
        overlayAttached = attached
        if (attached) {
            overlayRetriesExhausted = false
            lastDeliveryFailed = false
            fallbackKey = null
        }
        if (changed) {
            apply(OtpSession.snapshot(), null)
        }
    }

    fun reportOverlayFailed(host: Context? = null) {
        overlayRetriesExhausted = true
        overlayAttached = false
        apply(OtpSession.snapshot(), host)
    }

    private fun apply(snap: SessionSnapshot, startContext: Context?) {
        val pending = snap.pending
        val ctx = app ?: return
        val canDraw = Settings.canDrawOverlays(ctx)
        val plan = ConfirmPromptPolicy.plan(
            hasPending = pending != null,
            accessibilityConnected = snap.accessibilityConnected,
            overlayAttached = overlayAttached,
            overlayRetriesExhausted = overlayRetriesExhausted,
            canDrawOverlays = canDraw
        )
        main.removeCallbacks(waitForOverlay)
        if (plan.waitMs > 0) {
            main.postDelayed(waitForOverlay, plan.waitMs)
        }
        if (plan.cancelNotification) {
            ConfirmNotifier.cancel(ctx)
            fallbackKey = null
        }
        if (plan.surface == PromptSurface.NONE) {
            overlayAttached = false
            overlayRetriesExhausted = false
            ConfirmOverlayPresenter.hide()
            notifyUi()
            return
        }
        if (plan.surface == PromptSurface.WAIT_FOR_OVERLAY &&
            pending != null &&
            canDraw &&
            !snap.accessibilityConnected
        ) {
            ConfirmOverlayPresenter.show(startContext ?: ctx, pending)
        }
        if (plan.showNotification || plan.startConfirmActivity) {
            if (pending != null) {
                showFallback(startContext ?: ctx, pending)
            }
        }
        notifyUi()
    }

    private fun showFallback(host: Context, pending: PendingOtp) {
        val key = "${pending.packageName}|${pending.notificationKey}|${pending.code}"
        if (fallbackKey == key) return
        fallbackKey = key
        fallbackAttempted = true
        val posted = ConfirmNotifier.notify(host, pending)
        val startedActivity = ConfirmLauncher.start(host)
        lastDeliveryFailed = !posted && !startedActivity && !overlayAttached
    }

    private fun notifyUi() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiListeners.forEach { it() }
        } else {
            main.post { uiListeners.forEach { it() } }
        }
    }
}
