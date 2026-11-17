package com.wusper.auto.fill

/**
 * When the watcher should listen to notifications.
 * Watching starts on OTP focus or a manual start, then stays on until the user
 * turns the master switch off or explicitly stops watching.
 */
object ArmingPolicy {

    data class State(
        val masterEnabled: Boolean = true,
        val watching: Boolean = false,
        val otpFieldFocused: Boolean = false
    ) {
        fun isArmed(now: Long = 0L): Boolean {
            if (!masterEnabled) return false
            return watching
        }
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
        return state.copy(watching = true, otpFieldFocused = true)
    }

    fun onOtpFocusLost(state: State, now: Long = 0L): State {
        if (!state.masterEnabled) return state
        return state.copy(otpFieldFocused = false)
    }

    fun onLeftPage(state: State): State {
        if (!state.masterEnabled) return state
        // Leaving a page or switching apps must not stop watching.
        return state.copy(otpFieldFocused = false)
    }

    fun onManualArm(state: State, now: Long = 0L): State {
        if (!state.masterEnabled) return state
        return state.copy(watching = true)
    }

    fun onStopWatching(state: State): State {
        return state.copy(watching = false, otpFieldFocused = false)
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
