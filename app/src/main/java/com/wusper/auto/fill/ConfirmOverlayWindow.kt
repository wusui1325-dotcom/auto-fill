package com.wusper.auto.fill

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.wusper.auto.fill.databinding.ViewConfirmBinding

/**
 * Inflates and attaches the confirm half-screen through WindowManager.
 * The host context should be an AccessibilityService for TYPE_ACCESSIBILITY_OVERLAY,
 * and may be the application or a service for TYPE_APPLICATION_OVERLAY.
 * MainActivity does not need to be in the foreground.
 */
class ConfirmOverlayWindow {
    private var binding: ViewConfirmBinding? = null
    var root: FrameLayout? = null
        private set
    var attachedKind: OverlayWindowKind? = null
        private set
    private var windowManager: WindowManager? = null

    fun bind(host: Context, pending: PendingOtp) {
        val b = binding ?: inflate(host)
        b.confirmCode.text = pending.code
        b.confirmSource.text = host.getString(R.string.confirm_source, pending.source)
        b.btnFill.setOnClickListener {
            if (OtpSession.pending?.code != pending.code) {
                detach()
                return@setOnClickListener
            }
            detach()
            ConfirmPromptController.reportOverlayAttached(false)
            val accepted = OtpSession.confirmFill()
            if (!accepted) {
                Toast.makeText(host.applicationContext, R.string.fill_failed, Toast.LENGTH_SHORT).show()
            }
        }
        b.btnCancel.setOnClickListener {
            detach()
            ConfirmPromptController.reportOverlayAttached(false)
            OtpSession.cancelConfirm()
        }
        b.confirmRoot.setOnClickListener {
            detach()
            ConfirmPromptController.reportOverlayAttached(false)
            OtpSession.cancelConfirm()
        }
        b.confirmCard.setOnClickListener { /* swallow */ }
    }

    fun attach(host: Context, kind: OverlayWindowKind): Boolean {
        val view = root ?: inflate(host).root as FrameLayout
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (view.parent != null) {
            if (attachedKind == kind && windowManager === wm) {
                forceVisible()
                return true
            }
            detach()
        }
        return try {
            wm.addView(view, OverlayLayout.params(kind))
            windowManager = wm
            attachedKind = kind
            root = view
            forceVisible()
            true
        } catch (_: Exception) {
            attachedKind = null
            windowManager = null
            false
        }
    }

    fun isVisiblyAttached(): Boolean {
        val v = root
        return v != null && v.parent != null && v.isShown && v.visibility == View.VISIBLE
    }

    fun forceVisible() {
        val v = root ?: return
        v.visibility = View.VISIBLE
        v.alpha = 1f
        v.bringToFront()
        if (v.width == 0 || v.height == 0) {
            v.requestLayout()
        }
    }

    fun detach() {
        val v = root
        val wm = windowManager
        attachedKind = null
        windowManager = null
        if (v == null || v.parent == null || wm == null) return
        try {
            wm.removeViewImmediate(v)
        } catch (_: Exception) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
                // Already detached.
            }
        }
    }

    fun release() {
        detach()
        binding = null
        root = null
    }

    private fun inflate(host: Context): ViewConfirmBinding {
        val themed = DynamicColors.wrapContextIfAvailable(
            ContextThemeWrapper(host, R.style.Theme_AutoFill)
        )
        val created = ViewConfirmBinding.inflate(LayoutInflater.from(themed))
        binding = created
        root = created.root as FrameLayout
        return created
    }
}
