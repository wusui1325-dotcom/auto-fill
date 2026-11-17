package com.wusper.auto.fill

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Layout params for the half-screen confirm overlay.
 * Does not use FLAG_NOT_FOCUSABLE, so 填入 / 取消 stay tappable.
 */
object OverlayLayout {

    fun params(kind: OverlayWindowKind): WindowManager.LayoutParams {
        val type = when (kind) {
            OverlayWindowKind.ACCESSIBILITY ->
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            OverlayWindowKind.APPLICATION ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = 0
            params.fitInsetsSides = 0
            params.setFitInsetsIgnoringVisibility(true)
        }
        return params
    }
}
