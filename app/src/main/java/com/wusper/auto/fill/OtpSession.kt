package com.wusper.auto.fill

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

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
    const val PROMPT_TIMEOUT_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SessionSnapshot) -> Unit>()
    private val recentKeys = ArrayDeque<Pair<String, Long>>()
    private val lock = Any()

    @Volatile
    private var lastSnapshot: SessionSnapshot? = null

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

    private val expireArming = Runnable { notifyChanged() }
    private val expirePending = Runnable { cancelConfirm() }

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
        if (!enabled) clearPendingLocked()
        notifyChanged()
    }

    fun otpFocusGained() {
        val changed = update { ArmingPolicy.onOtpFocusGained(it) }
        if (changed) notifyChanged()
    }

    fun otpFocusLost(now: Long = System.currentTimeMillis()) {
        val changed = update { ArmingPolicy.onOtpFocusLost(it, now) }
        if (changed) notifyChanged()
    }

    fun leftPage() {
        val changed = update { ArmingPolicy.onLeftPage(it) }
        if (changed) notifyChanged()
    }

    fun manualArm(now: Long = System.currentTimeMillis()) {
        update { ArmingPolicy.onManualArm(it, now) }
        notifyChanged()
    }

    fun stopWatching() {
        update { ArmingPolicy.onStopWatching(it) }
        clearPendingLocked()
        notifyChanged()
    }

    fun setListenerConnected(connected: Boolean) {
        if (listenerConnected == connected) return
        listenerConnected = connected
        notifyChanged()
    }

    fun setAccessibilityConnected(connected: Boolean) {
        if (accessibilityConnected == connected && (connected || pending == null)) return
        accessibilityConnected = connected
        if (!connected) clearPendingLocked()
        notifyChanged()
    }

    fun offerOtp(otp: PendingOtp, now: Long = System.currentTimeMillis()): Boolean {
        if (!arming.isArmed(now)) return false
        if (otp.code.isBlank()) return false
        val dedupKey = "${otp.packageName}|${otp.notificationKey}|${otp.code}"
        synchronized(lock) {
            pruneRecent(now)
            if (recentKeys.any { it.first == dedupKey }) return false
            recentKeys.addLast(dedupKey to now)
            pending = otp
        }
        main.removeCallbacks(expirePending)
        main.postDelayed(expirePending, PROMPT_TIMEOUT_MS)
        notifyChanged()
        return true
    }

    fun confirmFill(): Boolean {
        val current = synchronized(lock) {
            val value = pending
            pending = null
            value
        } ?: return false
        main.removeCallbacks(expirePending)
        notifyChanged()
        return fillHandler?.invoke(current) == true
    }

    fun cancelConfirm() {
        val had = synchronized(lock) {
            val value = pending
            pending = null
            value
        } != null
        main.removeCallbacks(expirePending)
        if (had) notifyChanged()
    }

    private fun clearPendingLocked() {
        synchronized(lock) { pending = null }
        main.removeCallbacks(expirePending)
    }

    private fun update(block: (ArmingPolicy.State) -> ArmingPolicy.State): Boolean {
        val next: ArmingPolicy.State
        val changed: Boolean
        synchronized(lock) {
            next = block(arming)
            changed = next != arming
            arming = next
        }
        scheduleArmExpiry(next)
        return changed
    }

    private fun scheduleArmExpiry(state: ArmingPolicy.State) {
        main.removeCallbacks(expireArming)
        val now = System.currentTimeMillis()
        val until = max(state.graceUntil, state.manualUntil)
        if (state.masterEnabled && !state.otpFieldFocused && until > now) {
            main.postDelayed(expireArming, until - now + 15L)
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
        if (snap == lastSnapshot) return
        lastSnapshot = snap
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
