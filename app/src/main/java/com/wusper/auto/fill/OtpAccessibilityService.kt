package com.wusper.auto.fill

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Detects OTP fields, shows the confirm prompt, and fills the focused field after the user agrees.
 * Accessibility is not used for anything else.
 */
class OtpAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var unobserve: (() -> Unit)? = null
    private var savedTarget: AccessibilityNodeInfo? = null
    private var lastFocusedWasOtp = false
    private var lastAppPackage: String? = null
    private var pendingWindowPkg: String? = null
    private var pendingFillCode: String? = null
    private var fillAttempts = 0
    private var fillInFlight = false
    private var fillFieldMisses = 0
    private var imePackages: Set<String> = emptySet()
    private var imePackagesAt = 0L

    private val debounceWindows = Runnable {
        handleWindowEvent(pendingWindowPkg, AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        OtpSession.setAccessibilityConnected(true)
        OtpSession.fillHandler = { pending -> scheduleFill(pending.code) }
        unobserve = OtpSession.observe { snap ->
            val pending = snap.pending
            if (pending == null) {
                hideOverlay()
            } else {
                // Only show the confirm overlay here. Walking the window tree
                // to find a field belongs in the fill path after the user taps
                // 填入; doing it now can stall the main thread and fall back
                // to the in-app confirm screen.
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

        // Track the current app for fill targeting, but never auto-stop watching.
        if (eventPkg != null && lastAppPackage != null && eventPkg != lastAppPackage) {
            lastFocusedWasOtp = false
            lastAppPackage = eventPkg
            OtpSession.otpFocusLost()
            return
        }

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
            if (root.packageName?.toString() == packageName) {
                root.recycleQuietly()
                return@forEach
            }
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
        if (root.packageName?.toString() == packageName) {
            root.recycleQuietly()
            return null
        }
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
        return try {
            // Skip labeledBy / extras work on text, buttons, and other non-fields
            // during window-tree walks so the confirm overlay is not delayed.
            if (!node.isEditable && !node.isPassword && !node.isFocused) return false
            OtpFieldDetector.isOtpField(node.toSnapshot())
        } catch (_: Exception) {
            false
        }
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
            maxLength = extrasMaxLength(),
            labelText = readLabeledText()
        )
    }

    private fun AccessibilityNodeInfo.readLabeledText(): String? {
        val label = try {
            labeledBy
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            return listOf(label.text?.toString(), label.contentDescription?.toString(), label.hintText?.toString())
                .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .joinToString(" ")
                .ifBlank { null }
        } catch (_: Exception) {
            return null
        } finally {
            label.recycleQuietly()
        }
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
        ConfirmOverlayPresenter.show(this, pending)
    }

    private fun hideOverlay() {
        ConfirmOverlayPresenter.hide()
    }

    private fun scheduleFill(code: String): Boolean {
        if (code.isBlank()) return false
        if (fillInFlight && pendingFillCode == code) {
            hideOverlay()
            return true
        }
        hideOverlay()
        pendingFillCode = code
        fillAttempts = 0
        fillFieldMisses = 0
        fillInFlight = true
        handler.removeCallbacks(fillAttempt)
        // Dismiss first, then fill on the next looper turn so the webpage can regain focus.
        handler.post(fillAttempt)
        return true
    }

    private val fillAttempt = object : Runnable {
        override fun run() {
            val code = pendingFillCode ?: return
            if (fillCode(code)) {
                pendingFillCode = null
                fillInFlight = false
                return
            }
            fillAttempts += 1
            val found = resolveTarget()
            val missedField = found == null
            if (found != null && found !== savedTarget) found.recycleQuietly()
            if (missedField) {
                fillFieldMisses += 1
                if (fillFieldMisses <= FILL_FIELD_RETRIES) {
                    handler.postDelayed(this, FILL_RETRY_MS)
                    return
                }
            } else if (fillAttempts < 2) {
                handler.postDelayed(this, FILL_RETRY_MS)
                return
            }
            pendingFillCode = null
            fillInFlight = false
            Toast.makeText(this@OtpAccessibilityService, R.string.fill_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fillCode(code: String): Boolean {
        val target = resolveTarget() ?: return false
        try {
            prepareFocus(target)
            val group = detectSplitGroup(target, code.length)
            if (group != null) {
                try {
                    if (fillSplitGroup(group, code)) return true
                } finally {
                    group.forEach { if (it !== target && it !== savedTarget) it.recycleQuietly() }
                }
            }
            if (fillWholeField(target, code)) return true
            val retry = detectSplitGroup(target, code.length)
            if (retry != null) {
                try {
                    if (fillSplitGroup(retry, code)) return true
                } finally {
                    retry.forEach { if (it !== target && it !== savedTarget) it.recycleQuietly() }
                }
            }
            return codePresentInNodeOrNearby(target, code)
        } finally {
            if (target !== savedTarget) target.recycleQuietly()
        }
    }

    private fun fillWholeField(target: AccessibilityNodeInfo, code: String): Boolean {
        if (!canAttemptFill(target)) return false
        prepareFocus(target)
        when (FillWritePolicy.beforeWrite(readableCompact(target), code)) {
            FillWriteAction.STOP_SUCCESS -> return true
            FillWriteAction.WRITE_SINGLE -> {
                if (FillWritePolicy.visibleState(readableCompact(target), code) == VisibleCodeState.DOUBLED) {
                    setText(target, code)
                    return true
                }
            }
            else -> { }
        }
        val setOk = setText(target, code)
        val afterText = readableCompact(target)
        val confirmed = FillWritePolicy.visibleState(afterText, code) == VisibleCodeState.EXACT ||
            codePresentInNodeOrNearby(target, code)
        when (FillWritePolicy.afterSetText(setOk, afterText, code, confirmed)) {
            FillWriteAction.STOP_SUCCESS -> return true
            FillWriteAction.WRITE_SINGLE -> {
                setText(target, code)
                return true
            }
            FillWriteAction.FALLBACK_PASTE -> {
                clearFieldForPaste(target)
                val pasteOk = pasteText(target, code)
                when (FillWritePolicy.afterPaste(pasteOk, readableCompact(target), code)) {
                    FillWriteAction.STOP_SUCCESS -> return true
                    FillWriteAction.WRITE_SINGLE -> {
                        setText(target, code)
                        return true
                    }
                    FillWriteAction.FALLBACK_TYPE -> {
                        if (typePerChar(target, code)) return true
                    }
                    else -> { }
                }
            }
            else -> { }
        }
        // Nearby-tree is only a last-resort success check after writing has stopped.
        return codePresentInNodeOrNearby(target, code)
    }

    private fun clearFieldForPaste(node: AccessibilityNodeInfo) {
        setText(node, "")
        try {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            val end = (node.text?.length ?: 0).coerceAtLeast(64)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (_: Exception) {
            // Best-effort; paste still runs after this.
        }
    }

    private fun canAttemptFill(node: AccessibilityNodeInfo): Boolean {
        if (isOurPackage(node) || isAddressBar(node)) return false
        return node.isEditable || node.isPassword || node.isFocused || isOtpNode(node) ||
            FillTargetHeuristics.isWebViewLike(node.className?.toString())
    }

    private fun prepareFocus(node: AccessibilityNodeInfo) {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            // Some web fields reject click; SET_TEXT / PASTE may still work.
        }
    }

    private fun typePerChar(node: AccessibilityNodeInfo, code: String): Boolean {
        if (code.isEmpty()) return false
        prepareFocus(node)
        setText(node, "")
        val acc = StringBuilder()
        for (ch in code) {
            acc.append(ch)
            val soFar = acc.toString()
            val setOk = setText(node, soFar)
            val typedVisible = readableCompact(node)
            val typedConfirmed = soFar.length >= 4 && (
                FillWritePolicy.visibleState(typedVisible, soFar) == VisibleCodeState.EXACT ||
                    codePresentInNodeOrNearby(node, soFar)
                )
            when (FillWritePolicy.afterSetText(setOk, typedVisible, soFar, typedConfirmed)) {
                FillWriteAction.STOP_SUCCESS -> continue
                FillWriteAction.WRITE_SINGLE -> {
                    setText(node, soFar)
                    continue
                }
                else -> { }
            }
            if (prefixMatches(node, soFar)) continue
            if (pasteText(node, ch.toString()) && prefixMatches(node, soFar)) continue
            val readable = readableCompact(node)
            if (readable.isEmpty() && setOk) continue
            if (readable.isNotEmpty() && !FillTargetHeuristics.codeFullyPresent(readable, soFar) &&
                !readable.startsWith(soFar.filter { it.isLetterOrDigit() }, ignoreCase = true)
            ) {
                return false
            }
        }
        return codePresentInNodeOrNearby(node, code)
    }

    private fun prefixMatches(node: AccessibilityNodeInfo, prefix: String): Boolean {
        val text = readableCompact(node)
        val want = FillTargetHeuristics.compact(prefix)
        if (want.isEmpty()) return false
        if (text.isEmpty()) return false
        return text.startsWith(want, ignoreCase = true) ||
            FillTargetHeuristics.codeFullyPresent(text, want)
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun codeFullyPresent(node: AccessibilityNodeInfo, code: String): Boolean {
        node.refresh()
        return FillTargetHeuristics.codeFullyPresent(node.text?.toString(), code)
    }

    private fun readableCompact(node: AccessibilityNodeInfo): String {
        node.refresh()
        return FillTargetHeuristics.compact(node.text?.toString())
    }

    private fun codePresentInNodeOrNearby(node: AccessibilityNodeInfo, code: String): Boolean {
        if (codeFullyPresent(node, code)) return true
        val budget = intArrayOf(24)
        if (textTreeContains(node, code, budget)) return true
        val parent = try {
            node.parent
        } catch (_: Exception) {
            null
        }
        if (parent != null) {
            try {
                val parentBudget = intArrayOf(24)
                if (textTreeContains(parent, code, parentBudget)) return true
            } finally {
                parent.recycleQuietly()
            }
        }
        return false
    }

    private fun textTreeContains(node: AccessibilityNodeInfo, code: String, budget: IntArray): Boolean {
        if (budget[0] <= 0) return false
        budget[0]--
        val blobs = listOf(
            node.text?.toString(),
            node.contentDescription?.toString()
        )
        if (blobs.any { FillTargetHeuristics.codeFullyPresent(it, code) }) return true
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) return false
            val child = node.getChild(i) ?: continue
            try {
                if (textTreeContains(child, code, budget)) return true
            } finally {
                child.recycleQuietly()
            }
        }
        return false
    }

    /**
     * After the confirm UI is dismissed, relocate the live OTP field.
     * Do not rely only on a node saved before the prompt appeared.
     */
    private fun resolveTarget(): AccessibilityNodeInfo? {
        val focused = focusedEditable()
        if (focused != null) {
            val usable = !isOurPackage(focused) && !isAddressBar(focused) && isPlausibleTarget(focused)
            if (usable) {
                rememberTarget(focused)
                focused.recycleQuietly()
                return savedTarget
            }
            focused.recycleQuietly()
        }
        findOtpInWindows()?.let { return it }
        val saved = savedTarget
        if (saved != null) {
            try {
                if (saved.refresh() && !isOurPackage(saved) && !isAddressBar(saved) &&
                    (saved.isVisibleToUser) && canAttemptFill(saved)
                ) {
                    return saved
                }
            } catch (_: Exception) {
                // Stale node.
            }
        }
        return findOtpInWindows(preferLastApp = false)
    }

    private fun isPlausibleTarget(node: AccessibilityNodeInfo): Boolean {
        return FillTargetHeuristics.isPlausibleFocusedFillTarget(
            isEditable = node.isEditable,
            isPassword = node.isPassword,
            isFocused = node.isFocused,
            isOtp = isOtpNode(node),
            isAddressOrSearch = isAddressBar(node),
            isWebView = FillTargetHeuristics.isWebViewLike(node.className?.toString()),
            maxLength = extrasMaxLengthOf(node),
            textLength = node.text?.length ?: 0
        )
    }

    private fun extrasMaxLengthOf(node: AccessibilityNodeInfo): Int? = node.extrasMaxLength()

    private fun isAddressBar(node: AccessibilityNodeInfo): Boolean {
        return FillTargetHeuristics.looksLikeAddressOrSearchBar(
            viewId = node.viewIdResourceName,
            hint = node.hintText?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            inputType = node.inputType
        )
    }

    private fun isOurPackage(node: AccessibilityNodeInfo): Boolean {
        return node.packageName?.toString() == packageName
    }

    private fun findOtpInWindows(preferLastApp: Boolean = true): AccessibilityNodeInfo? {
        val wantPkg = if (preferLastApp) lastAppPackage else null
        var budget = FILL_TREE_BUDGET
        windows?.forEach { window ->
            if (budget <= 0) return@forEach
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            } ?: return@forEach
            try {
                val pkg = root.packageName?.toString()
                if (pkg == packageName) return@forEach
                if (wantPkg != null && pkg != wantPkg) return@forEach
                val match = findOtpDescendant(root, budget)
                budget = match.second
                val node = match.first
                if (node != null) {
                    if (isAddressBar(node)) {
                        node.recycleQuietly()
                    } else {
                        rememberTarget(node)
                        node.recycleQuietly()
                        return savedTarget
                    }
                }
            } finally {
                root.recycleQuietly()
            }
        }
        return null
    }

    /**
     * Chrome and other browsers often keep the visible "驗證碼" label on a
     * sibling or parent, not on the input's own hint or labeled-by field.
     */
    private fun isNearbyLabeledOtp(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable && !node.isPassword) return false
        if (isAddressBar(node)) return false
        val parent = try {
            node.parent
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            val blobs = StringBuilder()
            parent.text?.let { blobs.append(it).append(' ') }
            parent.contentDescription?.let { blobs.append(it).append(' ') }
            parent.hintText?.let { blobs.append(it).append(' ') }
            val limit = minOf(parent.childCount, 12)
            for (i in 0 until limit) {
                val child = parent.getChild(i) ?: continue
                try {
                    if (child.isEditable || child.isPassword) continue
                    child.text?.let { blobs.append(it).append(' ') }
                    child.contentDescription?.let { blobs.append(it).append(' ') }
                    child.hintText?.let { blobs.append(it).append(' ') }
                } finally {
                    child.recycleQuietly()
                }
            }
            OtpFieldDetector.looksLikeOtpText(blobs.toString())
        } finally {
            parent.recycleQuietly()
        }
    }

    private fun findOtpDescendant(node: AccessibilityNodeInfo, budget: Int): Pair<AccessibilityNodeInfo?, Int> {
        var remaining = budget - 1
        if (remaining < 0) return null to 0
        val otp = try {
            isOtpNode(node) || isNearbyLabeledOtp(node)
        } catch (_: Exception) {
            false
        }
        if (otp) return obtainCopy(node) to remaining
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


    /**
     * Prefer pasting the full code into a first cell that can hold it, so the
     * page can spread the characters. If that leaves the whole code in cell 0,
     * clear and write one character per cell.
     */
    private fun fillSplitGroup(fields: List<AccessibilityNodeInfo>, code: String): Boolean {
        if (fields.size != code.length) return false
        val first = fields.first()
        if (SplitFillPolicy.firstCellAcceptsFullCode(extrasMaxLengthOf(first), code.length)) {
            first.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val setOk = setText(first, code)
            var state = SplitFillPolicy.groupState(fields.map { readableCompact(it) }, code)
            if (state == SplitGroupState.DISTRIBUTED) return true
            if (state == SplitGroupState.UNREADABLE || state == SplitGroupState.OTHER) {
                clearFieldForPaste(first)
                val pasteOk = pasteText(first, code)
                state = SplitFillPolicy.groupState(fields.map { readableCompact(it) }, code)
                if (state == SplitGroupState.DISTRIBUTED) return true
                if (state == SplitGroupState.UNREADABLE && pasteOk && !setOk) {
                    // Text stays unreadable; the paste may still have spread.
                    // Fall through to per-cell write after a clear so we do not
                    // leave the full code only in the first box.
                }
            }
            if (SplitFillPolicy.shouldClearFirstBeforePerCell(state) ||
                state == SplitGroupState.FIRST_HAS_ALL
            ) {
                clearFieldForPaste(first)
            }
        }
        return fillSplitFields(fields, code)
    }

    private fun fillSplitFields(fields: List<AccessibilityNodeInfo>, code: String): Boolean {
        if (fields.size != code.length) return false
        for (i in fields.indices) {
            val node = fields[i]
            val ch = code[i].toString()
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (FillWritePolicy.cellAlreadyHas(readableCompact(node), ch)) continue
            val setOk = setText(node, ch)
            when (FillWritePolicy.afterSetTextCell(setOk, readableCompact(node), ch)) {
                FillWriteAction.STOP_SUCCESS -> continue
                FillWriteAction.WRITE_SINGLE -> {
                    setText(node, ch)
                    continue
                }
                FillWriteAction.FALLBACK_PASTE -> {
                    if (FillWritePolicy.cellAlreadyHas(readableCompact(node), ch)) continue
                    clearFieldForPaste(node)
                    val pasteOk = pasteOneChar(node, ch)
                    when (FillWritePolicy.afterPaste(pasteOk, readableCompact(node), ch)) {
                        FillWriteAction.STOP_SUCCESS,
                        FillWriteAction.WRITE_SINGLE -> continue
                        else -> {
                            if (pasteOk && readableCompact(node).isEmpty()) continue
                            return false
                        }
                    }
                }
                else -> {
                    if (setOk && readableCompact(node).isEmpty()) continue
                    return false
                }
            }
        }
        return true
    }

    private fun cellHasChar(node: AccessibilityNodeInfo, ch: String): Boolean {
        return FillWritePolicy.cellAlreadyHas(readableCompact(node), ch)
    }

    private fun pasteOneChar(node: AccessibilityNodeInfo, ch: String): Boolean {
        return pasteText(node, ch)
    }

    /**
     * Paste into the field, then immediately restore (or clear) the clipboard.
     * The full code must never be left on the clipboard.
     */
    private fun pasteText(node: AccessibilityNodeInfo, value: String): Boolean {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val previous = try {
            cm.primaryClip
        } catch (_: Exception) {
            null
        }
        val hadClip = previous != null
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("", value))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            try {
                when {
                    hadClip && previous != null -> cm.setPrimaryClip(previous)
                    Build.VERSION.SDK_INT >= 28 -> cm.clearPrimaryClip()
                    else -> cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {
                // Best-effort restore so a pasted code does not stay on the clipboard.
            }
        }
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
        private const val FILL_TREE_BUDGET = 200
        private const val FILL_RETRY_MS = 60L
        private const val FILL_FIELD_RETRIES = 10

        // Platform stubs on some SDKs omit getAutofillHints(); call it reflectively when present.
        private val GET_AUTOFILL_HINTS = try {
            AccessibilityNodeInfo::class.java.getMethod("getAutofillHints")
        } catch (_: Exception) {
            null
        }
    }
}
