package com.wusper.auto.fill

import android.text.InputType

/**
 * Pure helpers for deciding whether a node is a safe fill target, and whether
 * a field already contains a code. Unit-tested without AccessibilityNodeInfo.
 */
object FillTargetHeuristics {

    fun compact(text: String?): String = text?.filter { it.isLetterOrDigit() }.orEmpty()

    fun codeFullyPresent(text: String?, code: String): Boolean {
        val hay = compact(text)
        val compactCode = compact(code)
        if (compactCode.isEmpty()) return false
        if (compactCode.length > 1 && hay.length <= 1) return false
        return hay.contains(compactCode, ignoreCase = true) && hay.length >= compactCode.length
    }

    fun looksLikeAddressOrSearchBar(
        viewId: String? = null,
        hint: String? = null,
        contentDescription: String? = null,
        className: String? = null,
        inputType: Int = 0
    ): Boolean {
        val blob = listOf(viewId, hint, contentDescription, className)
            .mapNotNull { it?.lowercase() }
            .joinToString(" ")
        if (blob.isBlank() && inputType == 0) return false
        val markers = listOf(
            "url_bar", "urlbar", "omnibox", "location_bar", "locationbar",
            "search_box", "searchbox", "address_bar", "addressbar",
            "search_or_type", "search or type", "type a url", "enter url",
            "網址", "网址", "搜尋或輸入", "搜索或输入"
        )
        if (markers.any { blob.contains(it) }) return true
        val cls = className.orEmpty()
        if (cls.contains("Omnibox", ignoreCase = true)) return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val klass = inputType and InputType.TYPE_MASK_CLASS
        if (klass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI) {
            return true
        }
        return false
    }

    /**
     * A focused field may still be the right target after the confirm UI is
     * dismissed, even if OTP hints are no longer obvious (common in WebView).
     */
    fun isWebViewLike(className: String?): Boolean {
        val name = className.orEmpty()
        return name.contains("WebView", ignoreCase = true) ||
            name.contains("chromium.content", ignoreCase = true)
    }

    fun isPlausibleFocusedFillTarget(
        isEditable: Boolean,
        isPassword: Boolean,
        isFocused: Boolean,
        isOtp: Boolean,
        isAddressOrSearch: Boolean,
        isWebView: Boolean = false,
        maxLength: Int? = null,
        textLength: Int = 0
    ): Boolean {
        if (isAddressOrSearch) return false
        if (isOtp) return isEditable || isPassword || isFocused || isWebView
        if (isWebView && isFocused) return true
        if (!isEditable && !isPassword) return false
        if (maxLength != null && maxLength in 4..8) return true
        if (textLength in 0..8 && isFocused) return true
        return isFocused && (isEditable || isPassword)
    }

    fun extractOtpAuthUri(raw: String?): String? = extractImportUri(raw, totpOnly = true)

    fun extractImportUri(raw: String?, totpOnly: Boolean = false): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().removePrefix("\uFEFF")
        if (!totpOnly) {
            val migration = MIGRATION_RE.find(text)?.value
            if (migration != null) {
                return migration.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
            }
        }
        val match = OTP_AUTH_RE.find(text) ?: return null
        return match.value.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
    }

    private val OTP_AUTH_RE = Regex(
        """otpauth://totp/[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )

    private val MIGRATION_RE = Regex(
        """otpauth-migration://[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )
}
