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
    }

    @Test
    fun armsOnOtpFocusAndStaysForGraceAfterUnfocus() {
        val now = 1_000_000L
        var state = ArmingPolicy.onOtpFocusGained(ArmingPolicy.State())
        assertTrue(state.isArmed(now))
        state = ArmingPolicy.onOtpFocusLost(state, now)
        assertTrue(state.isArmed(now + 1_000))
        assertTrue(state.isArmed(now + ArmingPolicy.GRACE_MS - 1))
        assertFalse(state.isArmed(now + ArmingPolicy.GRACE_MS))
    }

    @Test
    fun leavingPageStopsGraceButKeepsManualWatch() {
        val now = 5_000L
        var state = ArmingPolicy.onOtpFocusLost(ArmingPolicy.State(), now)
        assertTrue(state.isArmed(now + 10))
        state = ArmingPolicy.onLeftPage(state)
        assertFalse(state.isArmed(now + 10))

        state = ArmingPolicy.onManualArm(ArmingPolicy.State(), now)
        state = ArmingPolicy.onLeftPage(state)
        assertTrue(state.isArmed(now + 10))
        assertFalse(state.isArmed(now + ArmingPolicy.MANUAL_MS))
    }

    @Test
    fun masterOffStopsImmediately() {
        val now = 2_000L
        var state = ArmingPolicy.onOtpFocusGained(ArmingPolicy.State())
        state = ArmingPolicy.onMaster(false, state)
        assertFalse(state.isArmed(now))
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
