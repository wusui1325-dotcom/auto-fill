package com.wusper.auto.fill

/**
 * Pure decision helper for the confirm prompt.
 * Prefers the accessibility overlay; falls back to a heads-up notification
 * and ConfirmActivity only when the overlay cannot be shown.
 */
enum class PromptSurface {
    NONE,
    OVERLAY,
    WAIT_FOR_OVERLAY,
    FALLBACK
}

data class PromptPlan(
    val surface: PromptSurface,
    val showNotification: Boolean,
    val startConfirmActivity: Boolean,
    val cancelNotification: Boolean,
    val finishConfirmActivity: Boolean,
    val waitMs: Long = 0L
)

object ConfirmPromptPolicy {
    const val OVERLAY_WAIT_MS = 700L
    const val OVERLAY_RETRY_LIMIT = 4
    const val OVERLAY_RETRY_DELAY_MS = 150L

    fun plan(
        hasPending: Boolean,
        accessibilityConnected: Boolean,
        overlayAttached: Boolean,
        overlayRetriesExhausted: Boolean
    ): PromptPlan {
        if (!hasPending) {
            return PromptPlan(
                surface = PromptSurface.NONE,
                showNotification = false,
                startConfirmActivity = false,
                cancelNotification = true,
                finishConfirmActivity = true
            )
        }
        if (overlayAttached) {
            return PromptPlan(
                surface = PromptSurface.OVERLAY,
                showNotification = false,
                startConfirmActivity = false,
                cancelNotification = true,
                finishConfirmActivity = true
            )
        }
        if (accessibilityConnected && !overlayRetriesExhausted) {
            return PromptPlan(
                surface = PromptSurface.WAIT_FOR_OVERLAY,
                showNotification = false,
                startConfirmActivity = false,
                cancelNotification = false,
                finishConfirmActivity = false,
                waitMs = OVERLAY_WAIT_MS
            )
        }
        return PromptPlan(
            surface = PromptSurface.FALLBACK,
            showNotification = true,
            startConfirmActivity = true,
            cancelNotification = false,
            finishConfirmActivity = false
        )
    }
}
