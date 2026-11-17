package com.wusper.auto.fill

/**
 * Which confirm-overlay window types to try, and in what order.
 * The accessibility overlay can cover the current webpage while this app
 * is in the background. The application overlay (display over other apps)
 * is the fallback when accessibility is not connected, or if the
 * accessibility overlay cannot attach.
 */
enum class OverlayWindowKind {
    ACCESSIBILITY,
    APPLICATION
}

object OverlayWindowPolicy {

    /**
     * Try the accessibility overlay first when that service is the host.
     * If the user has granted display-over-apps, also try
     * TYPE_APPLICATION_OVERLAY, including when accessibility is not connected.
     * Do not prefer the application overlay first: some systems hide it
     * while this app is in the background, so the sheet only appears
     * after the user opens 填碼.
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
     * Never detach a visible overlay to switch types. If the accessibility
     * overlay is already up, keep it; the application overlay is only used
     * when accessibility did not attach.
     */
    fun shouldUpgradeToApplication(
        current: OverlayWindowKind?,
        remainingTypes: List<OverlayWindowKind>
    ): Boolean {
        return false
    }

    /**
     * A window is attached enough once it has a parent and is VISIBLE.
     * View.isShown can stay false for overlays before layout, or on some
     * systems, and must not be required or the prompt falls back to the app.
     */
    fun isAttachedEnough(
        hasParent: Boolean,
        visibilityVisible: Boolean
    ): Boolean = hasParent && visibilityVisible

    fun overlayHostIsApplication(kind: OverlayWindowKind): Boolean {
        return kind == OverlayWindowKind.APPLICATION
    }

    fun overlayButton(canDrawOverlays: Boolean): PermissionButtonKind {
        return if (canDrawOverlays) {
            PermissionButtonKind.CONNECTED
        } else {
            PermissionButtonKind.GRANT
        }
    }
}
