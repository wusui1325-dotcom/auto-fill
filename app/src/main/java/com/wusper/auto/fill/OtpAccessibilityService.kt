package com.wusper.auto.fill

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private var unobserve: (() -> Unit)? = null
    private var overlay: FrameLayout? = null
    private var overlayBinding: ViewConfirmBinding? = null
    private var shownKey: String? = null
    private var savedTarget: AccessibilityNodeInfo? = null
    private var lastFocusedWasOtp = false
    private var lastAppPackage: String? = null
    private var pendingWindowPkg: String? = null
    private var imePackages: Set<String> = emptySet()
    private var imePackagesAt = 0L

    private val debounceWindows = Runnable {
        handleWindowEvent(pendingWindowPkg, AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    }

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
        if (!OtpSession.arming.masterEnabled) return
        val eventPkg = event.packageName?.toString()
        if (isIgnoredPackage(eventPkg)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusEvent(event, treatAsUnfocus = true)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleFocusEvent(event, treatAsUnfocus = false)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                handleWindowEvent(eventPkg, event.eventType)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                pendingWindowPkg = eventPkg ?: pendingWindowPkg
                handler.removeCallbacks(debounceWindows)
                handler.postDelayed(debounceWindows, WINDOW_DEBOUNCE_MS)
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt; listening is gated by arming state.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        overlayBinding = null
        OtpSession.fillHandler = null
        unobserve?.invoke()
        unobserve = null
        recycleTarget()
        OtpSession.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        overlayBinding = null
        recycleTarget()
        super.onDestroy()
    }

    private fun handleFocusEvent(event: AccessibilityEvent, treatAsUnfocus: Boolean) {
        val source = event.source
        try {
            val node = source ?: if (treatAsUnfocus) focusedEditable() else null
            val ownedFallback = node != null && node !== source
            try {
                val otp = node != null && isOtpNode(node)
                if (otp && node != null) {
                    rememberTarget(node)
                    lastFocusedWasOtp = true
                    lastAppPackage = node.packageName?.toString() ?: event.packageName?.toString()
                    OtpSession.otpFocusGained()
                } else if (treatAsUnfocus && lastFocusedWasOtp) {
                    lastFocusedWasOtp = false
                    OtpSession.otpFocusLost()
                }
            } finally {
                if (ownedFallback) node?.recycleQuietly()
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

        if (!OtpSession.isArmed() && !lastFocusedWasOtp) return

        if (eventPkg != null && isIgnoredPackage(eventPkg)) {
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

    private fun isIgnoredPackage(pkg: String?): Boolean {
        return ArmingPolicy.isTransientPackage(pkg, packageName) || isImePackage(pkg)
    }

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (ArmingPolicy.isTransientPackage(pkg, packageName)) return true
        val now = System.currentTimeMillis()
        if (now - imePackagesAt > IME_CACHE_MS) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imePackages = imm?.inputMethodList?.mapNotNull { it.packageName }?.toSet().orEmpty()
            imePackagesAt = now
        }
        return pkg in imePackages
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
                if (focus != null) {
                    try {
                        if (focus.isEditable || focus.isPassword || isOtpNode(focus)) {
                            return obtainCopy(focus)
                        }
                    } finally {
                        focus.recycleQuietly()
                    }
                }
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
            if (focus != null) {
                try {
                    if (focus.isEditable || focus.isPassword || isOtpNode(focus)) {
                        return obtainCopy(focus)
                    }
                } finally {
                    focus.recycleQuietly()
                }
            }
        } finally {
            root.recycleQuietly()
        }
        return null
    }

    private fun isOtpNode(node: AccessibilityNodeInfo): Boolean {
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
            isFocused = isFocused,
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
        savedTarget = obtainCopy(node)
    }

    private fun recycleTarget() {
        savedTarget?.recycleQuietly()
        savedTarget = null
    }

    private fun showPrompt(pending: PendingOtp) {
        if (overlay != null && shownKey == pendingKey(pending)) return
        val binding = overlayBinding ?: inflateOverlay()
        binding.confirmCode.text = pending.code
        binding.confirmSource.text = getString(R.string.confirm_source, pending.source)
        binding.btnFill.setOnClickListener { accept(pending) }
        binding.btnCancel.setOnClickListener { cancel() }
        binding.confirmRoot.setOnClickListener { cancel() }
        binding.confirmCard.setOnClickListener { /* swallow */ }

        val root = binding.root as FrameLayout
        if (overlay !== root || root.parent == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            try {
                if (root.parent != null) {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(root)
                }
                addOverlay(root, params)
                overlay = root
            } catch (_: Exception) {
                overlay = null
                shownKey = null
                startConfirmActivity()
                return
            }
        }
        shownKey = pendingKey(pending)
        root.visibility = android.view.View.VISIBLE
    }

    private fun inflateOverlay(): ViewConfirmBinding {
        val themed = DynamicColors.wrapContextIfAvailable(
            ContextThemeWrapper(this, R.style.Theme_AutoFill)
        )
        val binding = ViewConfirmBinding.inflate(LayoutInflater.from(themed))
        overlayBinding = binding
        return binding
    }

    private fun addOverlay(view: FrameLayout, params: WindowManager.LayoutParams) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, params)
    }

    private fun hideOverlay() {
        shownKey = null
        val view = overlay ?: return
        overlay = null
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
            // Fail open: timeout will clear the pending prompt; the user can type the code.
        }
    }

    private fun accept(pending: PendingOtp) {
        if (OtpSession.pending?.code != pending.code) {
            hideOverlay()
            return
        }
        val ok = OtpSession.confirmFill()
        if (!ok) {
            Toast.makeText(this, R.string.fill_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancel() {
        hideOverlay()
        OtpSession.cancelConfirm()
    }

    private fun fillCode(code: String): Boolean {
        val target = resolveTarget() ?: return false
        try {
            val group = detectSplitGroup(target, code.length)
            if (group != null) {
                try {
                    return fillSplitFields(group, code)
                } finally {
                    group.forEach { if (it !== target && it !== savedTarget) it.recycleQuietly() }
                }
            }
            if (fillWholeField(target, code)) return true
            // Whole-field SET_TEXT may keep only one character; do not treat that as success.
            val retry = detectSplitGroup(target, code.length)
            if (retry != null) {
                try {
                    return fillSplitFields(retry, code)
                } finally {
                    retry.forEach { if (it !== target && it !== savedTarget) it.recycleQuietly() }
                }
            }
            return codeFullyPresent(target, code)
        } finally {
            if (target !== savedTarget) target.recycleQuietly()
        }
    }

    private fun fillWholeField(target: AccessibilityNodeInfo, code: String): Boolean {
        if (!target.isEditable && !target.isPassword && !target.isFocused) return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        setText(target, code)
        target.refresh()
        return codeFullyPresent(target, code)
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun codeFullyPresent(node: AccessibilityNodeInfo, code: String): Boolean {
        node.refresh()
        val text = node.text?.toString()?.filter { it.isLetterOrDigit() }.orEmpty()
        val compact = code.filter { it.isLetterOrDigit() }
        if (compact.isEmpty()) return false
        if (compact.length > 1 && text.length <= 1) return false
        return text.contains(compact, ignoreCase = true) && text.length >= compact.length
    }

    private fun resolveTarget(): AccessibilityNodeInfo? {
        val saved = savedTarget
        if (saved != null) {
            try {
                if (saved.refresh() && (saved.isEditable || saved.isPassword || isOtpNode(saved))) {
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
        var budget = TREE_BUDGET
        windows?.forEach { window ->
            if (budget <= 0) return@forEach
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            } ?: return@forEach
            try {
                if (wantPkg != null && root.packageName?.toString() != wantPkg) return@forEach
                val match = findOtpDescendant(root, budget)
                budget = match.second
                val node = match.first
                if (node != null) {
                    rememberTarget(node)
                    node.recycleQuietly()
                    return savedTarget
                }
            } finally {
                root.recycleQuietly()
            }
        }
        return null
    }

    private fun findOtpDescendant(node: AccessibilityNodeInfo, budget: Int): Pair<AccessibilityNodeInfo?, Int> {
        var remaining = budget - 1
        if (remaining < 0) return null to 0
        if (isOtpNode(node)) return obtainCopy(node) to remaining
        for (i in 0 until node.childCount) {
            if (remaining <= 0) break
            val child = node.getChild(i) ?: continue
            val found = try {
                findOtpDescendant(child, remaining)
            } finally {
                child.recycleQuietly()
            }
            remaining = found.second
            if (found.first != null) return found
        }
        return null to remaining
    }

    /**
     * Detect a row of 4–8 single-character OTP boxes. Only used when the group
     * clearly looks like one-box-per-digit; otherwise we leave filling to SET_TEXT.
     */
    /**
     * Detect 4–8 single-character OTP cells, including wrapped rows and slight
     * vertical offset. Collects from the parent and a limited grandparent walk;
     * does not click-guess unrelated boxes.
     */
    private fun detectSplitGroup(start: AccessibilityNodeInfo, expected: Int): List<AccessibilityNodeInfo>? {
        if (expected !in 4..8) return null
        val collected = mutableListOf<AccessibilityNodeInfo>()
        var handover = false
        try {
            val parent = start.parent
            if (parent != null) {
                try {
                    collectEditables(parent, collected, depth = 0, maxDepth = 2, budget = intArrayOf(48))
                    if (collected.size < expected) {
                        val grand = parent.parent
                        if (grand != null) {
                            try {
                                collectEditables(grand, collected, depth = 0, maxDepth = 3, budget = intArrayOf(64))
                            } finally {
                                grand.recycleQuietly()
                            }
                        }
                    }
                } finally {
                    parent.recycleQuietly()
                }
            }
            if (collected.size < expected) return null
            val snapshots = collected.mapIndexed { index, node -> node.toCellSnapshot(index) }
            val startId = collected.indexOfFirst { sameNode(it, start) }.takeIf { it >= 0 }
            val ids = SplitOtpGroupDetector.detect(snapshots, expected, startId) ?: return null
            val selected = ids.map { collected[it] }
            handover = true
            // Recycle nodes that were not selected.
            collected.forEach { node ->
                if (node !in selected && node !== start && node !== savedTarget) {
                    node.recycleQuietly()
                }
            }
            return selected
        } finally {
            if (!handover) {
                collected.forEach { if (it !== start && it !== savedTarget) it.recycleQuietly() }
            }
        }
    }

    private fun collectEditables(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        maxDepth: Int,
        budget: IntArray
    ) {
        if (budget[0] <= 0) return
        budget[0]--
        if (node.isEditable || node.isPassword) {
            if (out.none { sameNode(it, node) }) {
                out += obtainCopy(node)
            }
        }
        if (depth >= maxDepth) return
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) return
            val child = node.getChild(i) ?: continue
            try {
                collectEditables(child, out, depth + 1, maxDepth, budget)
            } finally {
                child.recycleQuietly()
            }
        }
    }

    private fun sameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        if (a === b) return true
        val idA = a.viewIdResourceName
        val idB = b.viewIdResourceName
        if (!idA.isNullOrBlank() && idA == idB) {
            val ra = Rect()
            val rb = Rect()
            a.getBoundsInScreen(ra)
            b.getBoundsInScreen(rb)
            return ra == rb
        }
        val ra = Rect()
        val rb = Rect()
        a.getBoundsInScreen(ra)
        b.getBoundsInScreen(rb)
        return ra == rb && a.className == b.className
    }

    private fun AccessibilityNodeInfo.toCellSnapshot(id: Int): CellSnapshot {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return CellSnapshot(
            id = id,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            maxLength = extrasMaxLength(),
            textLength = text?.length ?: 0,
            isEditable = isEditable,
            isPassword = isPassword,
            className = className?.toString(),
            hint = hintText?.toString(),
            viewId = viewIdResourceName,
            autofillHints = readAutofillHints()
        )
    }

    private fun fillSplitFields(fields: List<AccessibilityNodeInfo>, code: String): Boolean {
        if (fields.size != code.length) return false
        for (i in fields.indices) {
            val node = fields[i]
            val ch = code[i].toString()
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (setText(node, ch) && cellHasChar(node, ch)) continue
            if (pasteOneChar(node, ch) && cellHasChar(node, ch)) continue
            return false
        }
        return true
    }

    private fun cellHasChar(node: AccessibilityNodeInfo, ch: String): Boolean {
        node.refresh()
        val text = node.text?.toString()?.filter { it.isLetterOrDigit() }.orEmpty()
        val want = ch.filter { it.isLetterOrDigit() }
        return want.isNotEmpty() && text.equals(want, ignoreCase = true)
    }

    private fun pasteOneChar(node: AccessibilityNodeInfo, ch: String): Boolean {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val previous = try {
            cm.primaryClip
        } catch (_: Exception) {
            null
        }
        val hadClip = previous != null
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("", ch))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            try {
                when {
                    hadClip && previous != null -> cm.setPrimaryClip(previous)
                    android.os.Build.VERSION.SDK_INT >= 28 -> cm.clearPrimaryClip()
                    else -> cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {
                // Best-effort restore; the pasted character was a single code cell.
            }
        }
    }

    private fun pendingKey(pending: PendingOtp): String {
        return "${pending.packageName}|${pending.notificationKey}|${pending.code}"
    }

    private fun obtainCopy(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        @Suppress("DEPRECATION")
        return AccessibilityNodeInfo.obtain(node)
    }

    private fun AccessibilityNodeInfo.recycleQuietly() {
        if (Build.VERSION.SDK_INT >= 33) return
        try {
            @Suppress("DEPRECATION")
            recycle()
        } catch (_: Exception) {
            // Already recycled.
        }
    }

    companion object {
        private const val WINDOW_DEBOUNCE_MS = 120L
        private const val IME_CACHE_MS = 60_000L
        private const val TREE_BUDGET = 80

        // Platform stubs on some SDKs omit getAutofillHints(); call it reflectively when present.
        private val GET_AUTOFILL_HINTS = try {
            AccessibilityNodeInfo::class.java.getMethod("getAutofillHints")
        } catch (_: Exception) {
            null
        }
    }
}
