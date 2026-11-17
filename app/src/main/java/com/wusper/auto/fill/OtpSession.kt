package com.wusper.auto.fill

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

data class PendingOtp(
    val code: String,
    val source: String,
    val packageName: String,
    val notificationKey: String,
    val postedAt: Long
)

data class SessionSnapshot(
    val masterEnabled: Boolean,
    val armed: Boolean,
    val listenerConnected: Boolean,
    val accessibilityConnected: Boolean,
    val pending: PendingOtp?
)

/**
 * In-process session between the notification listener, accessibility service, and UI.
 * Does not persist notification bodies or write codes to the clipboard.
 */
object OtpSession {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SessionSnapshot) -> Unit>()
    private val recentKeys = ArrayDeque<Pair<String, Long>>()

    @Volatile
    var arming: ArmingPolicy.State = ArmingPolicy.State()
        private set

    @Volatile
    var listenerConnected: Boolean = false
        private set

    @Volatile
    var accessibilityConnected: Boolean = false
        private set

    @Volatile
    var pending: PendingOtp? = null
        private set

    @Volatile
    var fillHandler: ((PendingOtp) -> Boolean)? = null

    fun snapshot(now: Long = System.currentTimeMillis()): SessionSnapshot {
        return SessionSnapshot(
            masterEnabled = arming.masterEnabled,
            armed = arming.isArmed(now),
            listenerConnected = listenerConnected,
            accessibilityConnected = accessibilityConnected,
            pending = pending
        )
    }

    fun isArmed(now: Long = System.currentTimeMillis()): Boolean = arming.isArmed(now)

    fun observe(listener: (SessionSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        main.post { listener(snapshot()) }
        return { listeners.remove(listener) }
    }

    fun setMasterEnabled(enabled: Boolean) {
        update { ArmingPolicy.onMaster(enabled, it) }
        if (!enabled) {
            pending = null
        }
        notifyChanged()
    }

    fun otpFocusGained() {
        update { ArmingPolicy.onOtpFocusGained(it) }
        notifyChanged()
    }

    fun otpFocusLost(now: Long = System.currentTimeMillis()) {
        update { ArmingPolicy.onOtpFocusLost(it, now) }
        notifyChanged()
    }

    fun leftPage() {
        update { ArmingPolicy.onLeftPage(it) }
        notifyChanged()
    }

    fun manualArm(now: Long = System.currentTimeMillis()) {
        update { ArmingPolicy.onManualArm(it, now) }
        notifyChanged()
    }

    fun stopWatching() {
        update { ArmingPolicy.onStopWatching(it) }
        pending = null
        notifyChanged()
    }

    fun setListenerConnected(connected: Boolean) {
        listenerConnected = connected
        notifyChanged()
    }

    fun setAccessibilityConnected(connected: Boolean) {
        accessibilityConnected = connected
        if (!connected) {
            pending = null
        }
        notifyChanged()
    }

    fun offerOtp(otp: PendingOtp, now: Long = System.currentTimeMillis()): Boolean {
        if (!arming.isArmed(now)) return false
        if (otp.code.isBlank()) return false
        val dedupKey = "${otp.packageName}|${otp.notificationKey}|${otp.code}"
        pruneRecent(now)
        if (recentKeys.any { it.first == dedupKey }) return false
        recentKeys.addLast(dedupKey to now)
        pending = otp
        notifyChanged()
        return true
    }

    fun confirmFill(): Boolean {
        val current = pending ?: return false
        pending = null
        notifyChanged()
        return fillHandler?.invoke(current) == true
    }

    fun cancelConfirm() {
        pending = null
        notifyChanged()
    }

    private fun update(block: (ArmingPolicy.State) -> ArmingPolicy.State) {
        synchronized(this) {
            arming = block(arming)
        }
    }

    private fun pruneRecent(now: Long) {
        val cutoff = now - 2 * 60_000L
        while (recentKeys.isNotEmpty() && recentKeys.first().second < cutoff) {
            recentKeys.removeFirst()
        }
        while (recentKeys.size > 20) {
            recentKeys.removeFirst()
        }
    }

    private fun notifyChanged() {
        val snap = snapshot()
        val deliver = Runnable {
            listeners.forEach { it(snap) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver.run()
        } else {
            main.post(deliver)
        }
    }
}
