package com.wusper.auto.fill

/**
 * Which confirm-overlay window types to try, and in what order.
 * Accessibility overlay needs no extra permission; application overlay
 * (display over other apps) is the reliable path once the user has granted it.
 */
enum class OverlayWindowKind {
    ACCESSIBILITY,
    APPLICATION
}

object OverlayWindowPolicy {

    /**
     * Prefer TYPE_APPLICATION_OVERLAY when display-over-apps is granted:
     * it reliably covers the current webpage, and trying the accessibility
     * overlay first would mean detaching a visible sheet to "upgrade".
     * The accessibility overlay remains the fallback when that permission
     * is missing, or if the application overlay cannot attach.
     */
    fun typesToTry(
        accessibilityConnected: Boolean,
        canDrawOverlays: Boolean
    ): List<OverlayWindowKind> {
        val types = ArrayList<OverlayWindowKind>(2)
        if (canDrawOverlays) {
            types.add(OverlayWindowKind.APPLICATION)
        }
        if (accessibilityConnected) {
            types.add(OverlayWindowKind.ACCESSIBILITY)
        }
        return types
    }

    fun canAttemptOverlay(
        accessibilityConnected: Boolean,
        canDrawOverlays: Boolean
    ): Boolean = typesToTry(accessibilityConnected, canDrawOverlays).isNotEmpty()

    /**
     * Do not detach a visible overlay to switch types. Application is already
     * tried first when the permission is granted, so this stays false.
     * Kept for the presenter and existing tests.
     */
    fun shouldUpgradeToApplication(
        current: OverlayWindowKind?,
        remainingTypes: List<OverlayWindowKind>
    ): Boolean {
        return false
    }

    fun overlayButton(canDrawOverlays: Boolean): PermissionButtonKind {
        return if (canDrawOverlays) {
            PermissionButtonKind.CONNECTED
        } else {
            PermissionButtonKind.GRANT
        }
    }
}
