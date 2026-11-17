package com.wusper.auto.fill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmingPolicyTest {

    @Test
    fun defaultIsNotArmedWithoutFocusOrManual() {
        val state = ArmingPolicy.State()
        assertFalse(state.isArmed(0L))
        assertTrue(state.masterEnabled)
        assertFalse(state.watching)
    }

    @Test
    fun armsOnOtpFocusAndStaysAfterUnfocusAndTimePassing() {
        val now = 1_000_000L
        var state = ArmingPolicy.onOtpFocusGained(ArmingPolicy.State())
        assertTrue(state.watching)
        assertTrue(state.isArmed(now))
        state = ArmingPolicy.onOtpFocusLost(state, now)
        assertTrue(state.isArmed(now + 1_000))
        assertTrue(state.isArmed(now + 24 * 60 * 60_000L))
        assertFalse(state.otpFieldFocused)
    }

    @Test
    fun leavingPageOrSwitchingAppsDoesNotStopWatching() {
        val now = 5_000L
        var state = ArmingPolicy.onOtpFocusGained(ArmingPolicy.State())
        state = ArmingPolicy.onLeftPage(state)
        assertTrue(state.isArmed(now + 10))
        assertTrue(state.watching)

        state = ArmingPolicy.onManualArm(ArmingPolicy.State(), now)
        state = ArmingPolicy.onLeftPage(state)
        assertTrue(state.isArmed(now + 10))
        assertTrue(state.isArmed(now + 90_000L))
    }

    @Test
    fun masterOffStopsImmediately() {
        val now = 2_000L
        var state = ArmingPolicy.onOtpFocusGained(ArmingPolicy.State())
        state = ArmingPolicy.onMaster(false, state)
        assertFalse(state.isArmed(now))
        assertFalse(state.watching)
        state = ArmingPolicy.onOtpFocusGained(state)
        assertFalse(state.isArmed(now))
    }

    @Test
    fun stopWatchingClearsAutoAndManual() {
        val now = 3_000L
        var state = ArmingPolicy.onManualArm(ArmingPolicy.onOtpFocusGained(ArmingPolicy.State()), now)
        assertTrue(state.isArmed(now))
        state = ArmingPolicy.onStopWatching(state)
        assertFalse(state.isArmed(now))
        assertFalse(state.watching)
    }

    @Test
    fun transientPackagesAreIgnoredForPageLeave() {
        val self = "com.wusper.auto.fill"
        assertTrue(ArmingPolicy.isTransientPackage("com.android.systemui", self))
        assertTrue(ArmingPolicy.isTransientPackage("com.google.android.inputmethod.latin", self))
        assertTrue(ArmingPolicy.isTransientPackage(self, self))
        assertFalse(ArmingPolicy.isTransientPackage("com.android.chrome", self))
        assertFalse(ArmingPolicy.isTransientPackage("com.tencent.mm", self))
    }
}
