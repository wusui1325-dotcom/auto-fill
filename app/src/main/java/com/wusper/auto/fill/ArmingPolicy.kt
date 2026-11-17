package com.wusper.auto.fill

/**
 * When the watcher should listen to notifications.
 * Auto-arm on OTP focus, stay armed briefly after unfocus, stop when leaving the page.
 */
object ArmingPolicy {
    const val GRACE_MS = 80_000L
    const val MANUAL_MS = 90_000L

    data class State(
        val masterEnabled: Boolean = true,
        val otpFieldFocused: Boolean = false,
        val graceUntil: Long = 0L,
        val manualUntil: Long = 0L
    ) {
        fun isArmed(now: Long): Boolean {
            if (!masterEnabled) return false
            if (otpFieldFocused) return true
            if (now < graceUntil) return true
            if (now < manualUntil) return true
            return false
        }

        fun isManual(now: Long): Boolean = masterEnabled && now < manualUntil
    }

    fun onMaster(enabled: Boolean, state: State): State {
        return if (enabled) {
            state.copy(masterEnabled = true)
        } else {
            State(masterEnabled = false)
        }
    }

    fun onOtpFocusGained(state: State): State {
        if (!state.masterEnabled) return state
        return state.copy(otpFieldFocused = true)
    }

    fun onOtpFocusLost(state: State, now: Long): State {
        if (!state.masterEnabled) return state
        return state.copy(otpFieldFocused = false, graceUntil = now + GRACE_MS)
    }

    fun onLeftPage(state: State): State {
        if (!state.masterEnabled) return state
        // Manual watch continues until its own timeout; auto grace stops with the page.
        return state.copy(otpFieldFocused = false, graceUntil = 0L)
    }

    fun onManualArm(state: State, now: Long): State {
        if (!state.masterEnabled) return state
        return state.copy(manualUntil = now + MANUAL_MS)
    }

    fun onStopWatching(state: State): State {
        return state.copy(otpFieldFocused = false, graceUntil = 0L, manualUntil = 0L)
    }

    fun isTransientPackage(packageName: String?, selfPackage: String): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return true
        if (pkg == selfPackage) return true
        if (pkg == "android" || pkg == "com.android.systemui") return true
        if (pkg == "com.android.permissioncontroller") return true
        if (pkg == "com.google.android.permissioncontroller") return true
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains(".ime", ignoreCase = true)) return true
        if (pkg.endsWith(".latin")) return true
        return false
    }
}
