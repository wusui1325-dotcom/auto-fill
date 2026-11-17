package com.wusper.auto.fill

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillTargetHeuristicsTest {

    @Test
    fun codeFullyPresentRejectsTruncation() {
        assertFalse(FillTargetHeuristics.codeFullyPresent("1", "123456"))
        assertFalse(FillTargetHeuristics.codeFullyPresent("", "123456"))
        assertTrue(FillTargetHeuristics.codeFullyPresent("123456", "123456"))
        assertTrue(FillTargetHeuristics.codeFullyPresent("12 34 56", "123456"))
    }

    @Test
    fun detectsChromeAddressBar() {
        assertTrue(
            FillTargetHeuristics.looksLikeAddressOrSearchBar(
                viewId = "com.android.chrome:id/url_bar",
                hint = "Search or type web address"
            )
        )
        assertTrue(
            FillTargetHeuristics.looksLikeAddressOrSearchBar(
                className = "org.chromium.chrome.browser.omnibox.UrlBar",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
        assertFalse(
            FillTargetHeuristics.looksLikeAddressOrSearchBar(
                viewId = "com.android.chrome:id/otp_input",
                hint = "Enter code"
            )
        )
    }

    @Test
    fun focusedShortFieldIsPlausibleButAddressBarIsNot() {
        assertTrue(
            FillTargetHeuristics.isPlausibleFocusedFillTarget(
                isEditable = true,
                isPassword = false,
                isFocused = true,
                isOtp = false,
                isAddressOrSearch = false,
                maxLength = 6,
                textLength = 0
            )
        )
        assertFalse(
            FillTargetHeuristics.isPlausibleFocusedFillTarget(
                isEditable = true,
                isPassword = false,
                isFocused = true,
                isOtp = false,
                isAddressOrSearch = true,
                maxLength = 8,
                textLength = 0
            )
        )
    }

    @Test
    fun focusedWebViewIsPlausible() {
        assertTrue(FillTargetHeuristics.isWebViewLike("android.webkit.WebView"))
        assertTrue(
            FillTargetHeuristics.isPlausibleFocusedFillTarget(
                isEditable = false,
                isPassword = false,
                isFocused = true,
                isOtp = false,
                isAddressOrSearch = false,
                isWebView = true
            )
        )
    }

    @Test
    fun extractsOtpAuthUriFromQrPayload() {
        val uri = "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        assertEquals(uri, FillTargetHeuristics.extractOtpAuthUri(uri))
        assertEquals(uri, FillTargetHeuristics.extractOtpAuthUri("  $uri\n"))
        assertEquals(
            uri,
            FillTargetHeuristics.extractOtpAuthUri("Account\n$uri")
        )
        assertNull(FillTargetHeuristics.extractOtpAuthUri("https://example.com"))
        assertNull(FillTargetHeuristics.extractOtpAuthUri("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun extractsMigrationUriFromQrPayload() {
        val uri = "otpauth-migration://offline?data=Ci4KCkhlbGxvId6tvu8"
        assertEquals(uri, FillTargetHeuristics.extractImportUri("Export\n$uri"))
        assertNull(FillTargetHeuristics.extractOtpAuthUri(uri))
    }
}

