package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowPolicyTest {

    @Test
    fun accessibilityIsTriedFirstWhenConnected() {
        val types = OverlayWindowPolicy.typesToTry(
            accessibilityConnected = true,
            canDrawOverlays = true
        )
        assertEquals(
            listOf(OverlayWindowKind.ACCESSIBILITY, OverlayWindowKind.APPLICATION),
            types
        )
    }

    @Test
    fun applicationOnlyWhenAccessibilityIsDownButOverlayGranted() {
        val types = OverlayWindowPolicy.typesToTry(
            accessibilityConnected = false,
            canDrawOverlays = true
        )
        assertEquals(listOf(OverlayWindowKind.APPLICATION), types)
        assertTrue(OverlayWindowPolicy.canAttemptOverlay(false, true))
    }

    @Test
    fun accessibilityOnlyWhenOverlayPermissionMissing() {
        val types = OverlayWindowPolicy.typesToTry(
            accessibilityConnected = true,
            canDrawOverlays = false
        )
        assertEquals(listOf(OverlayWindowKind.ACCESSIBILITY), types)
    }

    @Test
    fun noOverlayTypesWhenNeitherPathIsAvailable() {
        assertTrue(
            OverlayWindowPolicy.typesToTry(
                accessibilityConnected = false,
                canDrawOverlays = false
            ).isEmpty()
        )
        assertFalse(OverlayWindowPolicy.canAttemptOverlay(false, false))
    }

    @Test
    fun doesNotDetachAVisibleOverlayToUpgrade() {
        assertFalse(
            OverlayWindowPolicy.shouldUpgradeToApplication(
                OverlayWindowKind.ACCESSIBILITY,
                listOf(OverlayWindowKind.APPLICATION)
            )
        )
        assertFalse(
            OverlayWindowPolicy.shouldUpgradeToApplication(
                OverlayWindowKind.APPLICATION,
                emptyList()
            )
        )
        assertFalse(
            OverlayWindowPolicy.shouldUpgradeToApplication(
                OverlayWindowKind.ACCESSIBILITY,
                emptyList()
            )
        )
    }

    @Test
    fun overlayButtonMatchesGrantState() {
        assertEquals(PermissionButtonKind.GRANT, OverlayWindowPolicy.overlayButton(false))
        assertTrue(ConnectionUi.buttonEnabled(OverlayWindowPolicy.overlayButton(false)))
        assertEquals(PermissionButtonKind.CONNECTED, OverlayWindowPolicy.overlayButton(true))
        assertFalse(ConnectionUi.buttonEnabled(OverlayWindowPolicy.overlayButton(true)))
    }

    @Test
    fun attachedEnoughDoesNotRequireIsShown() {
        assertTrue(OverlayWindowPolicy.isAttachedEnough(hasParent = true, visibilityVisible = true))
        assertFalse(OverlayWindowPolicy.isAttachedEnough(hasParent = false, visibilityVisible = true))
        assertFalse(OverlayWindowPolicy.isAttachedEnough(hasParent = true, visibilityVisible = false))
    }

    @Test
    fun applicationOverlayUsesApplicationWindowHost() {
        assertTrue(OverlayWindowPolicy.overlayHostIsApplication(OverlayWindowKind.APPLICATION))
        assertFalse(OverlayWindowPolicy.overlayHostIsApplication(OverlayWindowKind.ACCESSIBILITY))
    }
}
