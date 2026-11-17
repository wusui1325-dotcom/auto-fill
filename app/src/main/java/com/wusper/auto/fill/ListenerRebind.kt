package com.wusper.auto.fill

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * After an overlay update, the notification listener can stay enabled but unbound.
 * Wait briefly so a normal bind can win, then requestRebind once if still down.
 */
object ListenerRebind {
    const val DELAY_MS = 1_500L
    private const val MIN_INTERVAL_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var lastRequestAt = 0L

    fun scheduleIfNeeded(context: Context) {
        val app = context.applicationContext
        if (!Permissions.isNotificationListenerEnabled(app)) return
        if (OtpSession.listenerConnected) return
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            if (!Permissions.isNotificationListenerEnabled(app)) return@postDelayed
            if (OtpSession.listenerConnected) return@postDelayed
            val now = System.currentTimeMillis()
            if (now - lastRequestAt < MIN_INTERVAL_MS) return@postDelayed
            lastRequestAt = now
            Permissions.requestNotificationListenerRebind(app)
        }, DELAY_MS)
    }
}
