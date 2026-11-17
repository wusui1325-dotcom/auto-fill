package com.wusper.auto.fill

import android.text.InputType
/** Pure snapshot so OTP-field heuristics can be unit tested without AccessibilityNodeInfo. */
data class FieldSnapshot(
    val autofillHints: List<String> = emptyList(),
    val inputType: Int = 0,
    val hint: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val isEditable: Boolean = true,
    val isPassword: Boolean = false,
    val maxLength: Int? = null
)

object OtpFieldDetector {

    // Includes View.AUTOFILL_HINT_SMS_OTP ("smsOTP") and emailOTP without referencing API 27+ constants.
    private val HINT_OTP = setOf(
        "smsotp",
        "emailotp",
        "2faappotp",
        "sms_otp",
        "email_otp",
        "one-time-code",
        "onetimecode",
        "one_time_code"
    )

    private val KEYWORD_RE = Regex(
        """驗證碼|验证码|校驗碼|校验码|認證碼|认证码|確認碼|确认码|動態碼|动态码|動態密碼|动态密码|一次[性性]|簡訊碼|短信碼|安全碼|安全码|verification code|one[-\s]?time|passcode|\botp\b|\b2fa\b|auth(?:entication)? code|security code|signin code|sign-in code""",
        RegexOption.IGNORE_CASE
    )

    private val DIGIT_COUNT_RE = Regex(
        """(?:([4-8])\s*位(?:數|数)?(?:驗證|验证|認證|认证)?(?:碼|码)?|([4-8])[-\s]?digits?(?:\s+code)?)""",
        RegexOption.IGNORE_CASE
    )

    private val VIEW_ID_RE = Regex(
        """otp|sms_?code|smsotp|email_?code|verify_?code|verif(y|ication)|auth_?code|one_?time|onetime|yanzheng|yzm|pin_?code|security_?code""",
        RegexOption.IGNORE_CASE
    )

    fun isOtpField(field: FieldSnapshot): Boolean {
        if (!field.isEditable) return false
        if (hasAutofillOtpHint(field.autofillHints)) return true

        val blob = listOf(field.hint, field.text, field.contentDescription, field.viewId)
            .filterNotNull()
            .joinToString(" ")
        if (blob.isNotBlank() && KEYWORD_RE.containsMatchIn(blob)) return true
        if (blob.isNotBlank() && DIGIT_COUNT_RE.containsMatchIn(blob)) return true
        if (!field.viewId.isNullOrBlank() && VIEW_ID_RE.containsMatchIn(field.viewId)) return true

        val number = isNumberClass(field.inputType)
        val phone = isPhoneClass(field.inputType)
        if (phone && !KEYWORD_RE.containsMatchIn(blob)) return false

        val max = field.maxLength
        if (max != null && number) {
            if (max in 5..8) return true
            if (max == 4 && (field.isPassword || isNumberPassword(field.inputType))) return true
        }
        return false
    }

    internal fun hasAutofillOtpHint(hints: List<String>): Boolean {
        return hints.any { hint ->
            val n = hint.lowercase().replace(" ", "").replace("_", "")
            HINT_OTP.contains(hint.lowercase()) ||
                HINT_OTP.contains(n) ||
                n.contains("smsotp") ||
                n.contains("emailotp") ||
                n == "otp"
        }
    }

    private fun isNumberClass(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER
    }

    private fun isPhoneClass(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_PHONE
    }

    private fun isNumberPassword(inputType: Int): Boolean {
        if (!isNumberClass(inputType)) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
