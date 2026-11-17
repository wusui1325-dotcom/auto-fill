package com.wusper.auto.fill

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.wusper.auto.fill.databinding.ViewConfirmBinding

/**
 * Detects OTP fields, shows the confirm prompt, and fills the focused field after the user agrees.
 * Accessibility is not used for anything else.
 */
class OtpAccessibilityService : AccessibilityService() {

    private var unobserve: (() -> Unit)? = null
    private var overlay: FrameLayout? = null
    private var shownKey: String? = null
    private var savedTarget: AccessibilityNodeInfo? = null
    private var lastFocusedWasOtp = false
    private var lastAppPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        OtpSession.setAccessibilityConnected(true)
        OtpSession.fillHandler = { pending -> fillCode(pending.code) }
        unobserve = OtpSession.observe { snap ->
            val pending = snap.pending
            if (pending == null) {
                hideOverlay()
            } else if (shownKey != pendingKey(pending)) {
                showPrompt(pending)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPkg = event.packageName?.toString()
        if (ArmingPolicy.isTransientPackage(eventPkg, packageName)) {
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleFocusEvent(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowEvent(eventPkg, event.eventType)
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt; listening is gated by arming state.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        OtpSession.fillHandler = null
        unobserve?.invoke()
        unobserve = null
        recycleTarget()
        OtpSession.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        recycleTarget()
        super.onDestroy()
    }

    private fun handleFocusEvent(event: AccessibilityEvent) {
        val source = event.source
        try {
            val node = source ?: focusedEditable()
            val otp = node != null && isOtpNode(node)
            if (otp && node != null) {
                rememberTarget(node)
                lastFocusedWasOtp = true
                lastAppPackage = node.packageName?.toString() ?: event.packageName?.toString()
                OtpSession.otpFocusGained()
            } else if (lastFocusedWasOtp) {
                lastFocusedWasOtp = false
                OtpSession.otpFocusLost()
            }
        } finally {
            source?.recycleQuietly()
        }
    }

    private fun handleWindowEvent(eventPkg: String?, eventType: Int) {
        val focused = focusedEditable()
        try {
            if (focused != null && isOtpNode(focused)) {
                rememberTarget(focused)
                lastFocusedWasOtp = true
                lastAppPackage = focused.packageName?.toString() ?: eventPkg
                OtpSession.otpFocusGained()
                return
            }
        } finally {
            focused?.recycleQuietly()
        }

        if (!OtpSession.arming.masterEnabled) return
        if (!OtpSession.isArmed() && !lastFocusedWasOtp) return

        if (eventPkg != null && (ArmingPolicy.isTransientPackage(eventPkg, packageName) || isImePackage(eventPkg))) {
            if (lastFocusedWasOtp) {
                lastFocusedWasOtp = false
                OtpSession.otpFocusLost()
            }
            return
        }

        // IME / split-screen / overlay reshuffles should keep the grace period.
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (lastFocusedWasOtp) {
                lastFocusedWasOtp = false
                OtpSession.otpFocusLost()
            }
            return
        }

        if (eventPkg != null && lastAppPackage != null && eventPkg != lastAppPackage) {
            lastFocusedWasOtp = false
            lastAppPackage = eventPkg
            OtpSession.leftPage()
            return
        }

        // Same app, no OTP field visible: brief grace so a dialog does not instantly stop watching.
        if (lastFocusedWasOtp || OtpSession.arming.otpFieldFocused) {
            lastFocusedWasOtp = false
            OtpSession.otpFocusLost()
        }
    }

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (ArmingPolicy.isTransientPackage(pkg, packageName)) return true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.inputMethodList.any { it.packageName == pkg }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        windows?.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            } ?: return@forEach
            try {
                val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focus != null && focus.isEditable) {
                    return focus
                }
                focus?.recycleQuietly()
            } finally {
                root.recycleQuietly()
            }
        }
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focus != null && focus.isEditable) return focus
            focus?.recycleQuietly()
        } finally {
            root.recycleQuietly()
        }
        return null
    }

    private fun isOtpNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable && !node.isPassword) return false
        return OtpFieldDetector.isOtpField(node.toSnapshot())
    }

    private fun AccessibilityNodeInfo.toSnapshot(): FieldSnapshot {
        return FieldSnapshot(
            autofillHints = readAutofillHints(),
            inputType = inputType,
            hint = hintText?.toString(),
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            viewId = viewIdResourceName,
            className = className?.toString(),
            isEditable = isEditable,
            isPassword = isPassword,
            maxLength = extrasMaxLength()
        )
    }


    private fun AccessibilityNodeInfo.readAutofillHints(): List<String> {
        val fromMethod = try {
            val raw = GET_AUTOFILL_HINTS?.invoke(this) as? Array<*>
            raw?.mapNotNull { it?.toString() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (fromMethod.isNotEmpty()) return fromMethod
        val extras = extras ?: return emptyList()
        val extra = extras.getStringArray("android.view.View.autofillHints")
            ?: extras.getStringArray("autofillHints")
        return extra?.toList().orEmpty()
    }

    private fun AccessibilityNodeInfo.extrasMaxLength(): Int? {

        val extras = extras ?: return null
        val keys = arrayOf("maxLength", "android.view.View.maxLength", "androidx.core.view.maxLength")
        for (key in keys) {
            if (extras.containsKey(key)) {
                val value = extras.getInt(key, -1)
                if (value > 0) return value
            }
        }
        return null
    }

    private fun rememberTarget(node: AccessibilityNodeInfo) {
        recycleTarget()
        savedTarget = AccessibilityNodeInfo.obtain(node)
    }

    private fun recycleTarget() {
        savedTarget?.recycleQuietly()
        savedTarget = null
    }

    private fun showPrompt(pending: PendingOtp) {
        if (overlay != null && shownKey == pendingKey(pending)) return
        hideOverlay()
        val themed = DynamicColors.wrapContextIfAvailable(
            ContextThemeWrapper(this, R.style.Theme_AutoFill)
        )
        val binding = ViewConfirmBinding.inflate(LayoutInflater.from(themed))
        binding.confirmCode.text = pending.code
        binding.confirmSource.text = getString(R.string.confirm_source, pending.source)
        binding.btnFill.setOnClickListener { accept(pending) }
        binding.btnCancel.setOnClickListener { cancel() }
        binding.confirmRoot.setOnClickListener { cancel() }
        binding.confirmCard.setOnClickListener { /* swallow */ }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val root = binding.root as FrameLayout
        try {
            addOverlay(root, params)
            overlay = root
            shownKey = pendingKey(pending)
        } catch (_: Exception) {
            overlay = null
            shownKey = null
            startConfirmActivity()
        }
    }

    private fun addOverlay(view: FrameLayout, params: WindowManager.LayoutParams) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, params)
    }

    private fun hideOverlay() {
        val view = overlay ?: run {
            shownKey = null
            return
        }
        overlay = null
        shownKey = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {
                // Already detached.
            }
        }
    }

    private fun startConfirmActivity() {
        val intent = Intent(this, ConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fail open: the user can type the code themselves.
        }
    }

    private fun accept(pending: PendingOtp) {
        hideOverlay()
        if (OtpSession.pending?.code == pending.code) {
            val ok = OtpSession.confirmFill()
            if (!ok) {
                Toast.makeText(this, R.string.fill_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cancel() {
        hideOverlay()
        OtpSession.cancelConfirm()
    }

    private fun fillCode(code: String): Boolean {
        val target = resolveTarget() ?: return false
        try {
            if (!target.isEditable && !target.isPassword) return false
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
            // Best-effort: fill sequential single-character sibling fields.
            return fillSplitFields(target, code)
        } finally {
            // Keep savedTarget; resolveTarget may have returned a fresh copy.
            if (target !== savedTarget) {
                target.recycleQuietly()
            }
        }
    }

    private fun resolveTarget(): AccessibilityNodeInfo? {
        val saved = savedTarget
        if (saved != null) {
            try {
                if (saved.refresh() && (saved.isEditable || saved.isPassword)) {
                    return saved
                }
            } catch (_: Exception) {
                // Stale node.
            }
        }
        val focused = focusedEditable()
        if (focused != null && isOtpNode(focused)) {
            rememberTarget(focused)
            focused.recycleQuietly()
            return savedTarget
        }
        focused?.recycleQuietly()
        return findOtpInWindows()
    }

    private fun findOtpInWindows(): AccessibilityNodeInfo? {
        val wantPkg = lastAppPackage
        windows?.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            } ?: return@forEach
            try {
                if (wantPkg != null && root.packageName?.toString() != wantPkg) return@forEach
                val match = findOtpDescendant(root)
                if (match != null) {
                    rememberTarget(match)
                    match.recycleQuietly()
                    return savedTarget
                }
            } finally {
                root.recycleQuietly()
            }
        }
        return null
    }

    private fun findOtpDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isOtpNode(node)) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                findOtpDescendant(child)
            } finally {
                child.recycleQuietly()
            }
            if (found != null) return found
        }
        return null
    }

    private fun fillSplitFields(start: AccessibilityNodeInfo, code: String): Boolean {
        val parent = start.parent ?: return false
        try {
            val fields = mutableListOf<AccessibilityNodeInfo>()
            try {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    if (child.isEditable) {
                        fields.add(child)
                    } else {
                        child.recycleQuietly()
                    }
                }
                if (fields.size !in 4..8 || fields.size != code.length) return false
                var all = true
                for (i in fields.indices) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        code[i].toString()
                    )
                    if (!fields[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        all = false
                        break
                    }
                }
                return all
            } finally {
                fields.forEach { it.recycleQuietly() }
            }
        } finally {
            parent.recycleQuietly()
        }
    }

    private fun pendingKey(pending: PendingOtp): String {
        return "${pending.packageName}|${pending.notificationKey}|${pending.code}"
    }

    private fun AccessibilityNodeInfo.recycleQuietly() {
        if (Build.VERSION.SDK_INT >= 33) return
        try {
            recycle()
        } catch (_: Exception) {
            // Already recycled.
        }
    }

    companion object {
        // Platform stubs on some SDKs omit getAutofillHints(); call it reflectively when present.
        private val GET_AUTOFILL_HINTS = try {
            AccessibilityNodeInfo::class.java.getMethod("getAutofillHints")
        } catch (_: Exception) {
            null
        }
    }
}
