package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmPromptPolicyTest {

    @Test
    fun noPendingCancelsFallbackAndFinishesConfirm() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = false,
            accessibilityConnected = true,
            overlayAttached = false,
            overlayRetriesExhausted = false
        )
        assertEquals(PromptSurface.NONE, plan.surface)
        assertTrue(plan.cancelNotification)
        assertTrue(plan.finishConfirmActivity)
        assertFalse(plan.showNotification)
        assertFalse(plan.startConfirmActivity)
    }

    @Test
    fun attachedOverlayIsPreferredAndDropsFallback() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = true,
            overlayAttached = true,
            overlayRetriesExhausted = true
        )
        assertEquals(PromptSurface.OVERLAY, plan.surface)
        assertTrue(plan.cancelNotification)
        assertTrue(plan.finishConfirmActivity)
        assertFalse(plan.showNotification)
        assertFalse(plan.startConfirmActivity)
    }

    @Test
    fun connectedAccessibilityWaitsForOverlayBeforeFallback() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = true,
            overlayAttached = false,
            overlayRetriesExhausted = false
        )
        assertEquals(PromptSurface.WAIT_FOR_OVERLAY, plan.surface)
        assertEquals(ConfirmPromptPolicy.OVERLAY_WAIT_MS, plan.waitMs)
        assertFalse(plan.showNotification)
        assertFalse(plan.startConfirmActivity)
        assertFalse(plan.cancelNotification)
    }

    @Test
    fun fallbackWhenAccessibilityIsDisconnected() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = false,
            overlayAttached = false,
            overlayRetriesExhausted = false
        )
        assertEquals(PromptSurface.FALLBACK, plan.surface)
        assertTrue(plan.showNotification)
        assertTrue(plan.startConfirmActivity)
        assertFalse(plan.cancelNotification)
        assertFalse(plan.finishConfirmActivity)
    }

    @Test
    fun fallbackWhenOverlayRetriesAreExhausted() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = true,
            overlayAttached = false,
            overlayRetriesExhausted = true
        )
        assertEquals(PromptSurface.FALLBACK, plan.surface)
        assertTrue(plan.showNotification)
        assertTrue(plan.startConfirmActivity)
    }

    @Test
    fun overlayStillWinsIfAccessibilityLaterConnects() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = true,
            overlayAttached = true,
            overlayRetriesExhausted = false
        )
        assertEquals(PromptSurface.OVERLAY, plan.surface)
        assertTrue(plan.cancelNotification)
        assertTrue(plan.finishConfirmActivity)
    }

    @Test
    fun waitWhenOverlayPermissionGrantedButAccessibilityIsDown() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = false,
            overlayAttached = false,
            overlayRetriesExhausted = false,
            canDrawOverlays = true
        )
        assertEquals(PromptSurface.WAIT_FOR_OVERLAY, plan.surface)
        assertFalse(plan.showNotification)
        assertFalse(plan.startConfirmActivity)
    }

    @Test
    fun fallbackWhenNeitherOverlayPathIsAvailable() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = false,
            overlayAttached = false,
            overlayRetriesExhausted = false,
            canDrawOverlays = false
        )
        assertEquals(PromptSurface.FALLBACK, plan.surface)
        assertTrue(plan.showNotification)
        assertTrue(plan.startConfirmActivity)
    }

    @Test
    fun fallbackAfterOverlayRetriesExhaustedEvenWithDrawOverlays() {
        val plan = ConfirmPromptPolicy.plan(
            hasPending = true,
            accessibilityConnected = false,
            overlayAttached = false,
            overlayRetriesExhausted = true,
            canDrawOverlays = true
        )
        assertEquals(PromptSurface.FALLBACK, plan.surface)
        assertTrue(plan.showNotification)
    }
}
