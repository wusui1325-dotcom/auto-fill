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
     * Always try the accessibility overlay first when that service is connected.
     * If the user has granted display-over-apps, also try TYPE_APPLICATION_OVERLAY,
     * including when accessibility is not connected.
     */
    fun typesToTry(
        accessibilityConnected: Boolean,
        canDrawOverlays: Boolean
    ): List<OverlayWindowKind> {
        val types = ArrayList<OverlayWindowKind>(2)
        if (accessibilityConnected) {
            types.add(OverlayWindowKind.ACCESSIBILITY)
        }
        if (canDrawOverlays) {
            types.add(OverlayWindowKind.APPLICATION)
        }
        return types
    }

    fun canAttemptOverlay(
        accessibilityConnected: Boolean,
        canDrawOverlays: Boolean
    ): Boolean = typesToTry(accessibilityConnected, canDrawOverlays).isNotEmpty()

    /**
     * After an accessibility overlay appears to attach, still switch to the
     * application overlay when that permission is available, so the prompt
     * can cover the current webpage on systems that hide accessibility overlays.
     */
    fun shouldUpgradeToApplication(
        current: OverlayWindowKind?,
        remainingTypes: List<OverlayWindowKind>
    ): Boolean {
        return current == OverlayWindowKind.ACCESSIBILITY &&
            remainingTypes.contains(OverlayWindowKind.APPLICATION)
    }

    fun overlayButton(canDrawOverlays: Boolean): PermissionButtonKind {
        return if (canDrawOverlays) {
            PermissionButtonKind.CONNECTED
        } else {
            PermissionButtonKind.GRANT
        }
    }
}
