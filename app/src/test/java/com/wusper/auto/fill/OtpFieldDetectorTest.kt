package com.wusper.auto.fill

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpFieldDetectorTest {

    @Test
    fun detectsAutofillHints() {
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(autofillHints = listOf("smsOTP"))))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(autofillHints = listOf("emailOTP"))))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(autofillHints = listOf("one-time-code"))))
    }

    @Test
    fun detectsKeywordsInHintAndViewId() {
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(hint = "請輸入驗證碼")))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(hint = "请输入验证码")))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(contentDescription = "Enter verification code")))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(viewId = "com.shop:id/otp_input")))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(hint = "6位驗證碼")))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(hint = "6-digit code")))
    }

    @Test
    fun detectsNumberFieldsWithOtpLength() {
        val number = InputType.TYPE_CLASS_NUMBER
        assertTrue(
            OtpFieldDetector.isOtpField(
                FieldSnapshot(inputType = number, maxLength = 6)
            )
        )
        assertTrue(
            OtpFieldDetector.isOtpField(
                FieldSnapshot(
                    inputType = number or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                    isPassword = true,
                    maxLength = 4
                )
            )
        )
        assertFalse(
            OtpFieldDetector.isOtpField(
                FieldSnapshot(inputType = number, maxLength = 4)
            )
        )
    }

    @Test
    fun ignoresOrdinaryFields() {
        assertFalse(OtpFieldDetector.isOtpField(FieldSnapshot(isEditable = false, hint = "驗證碼")))
        assertFalse(
            OtpFieldDetector.isOtpField(
                FieldSnapshot(inputType = InputType.TYPE_CLASS_PHONE, hint = "Phone")
            )
        )
        assertFalse(
            OtpFieldDetector.isOtpField(
                FieldSnapshot(inputType = InputType.TYPE_CLASS_NUMBER, hint = "金額")
            )
        )
        assertFalse(
            OtpFieldDetector.isOtpField(
                FieldSnapshot(
                    inputType = InputType.TYPE_CLASS_TEXT,
                    hint = "Email",
                    viewId = "com.app:id/email"
                )
            )
        )
    }

    @Test
    fun detectsLabelText() {
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(labelText = "驗證碼（6 位數字）")))
        assertTrue(OtpFieldDetector.isOtpField(FieldSnapshot(labelText = "Verification code")))
        assertTrue(OtpFieldDetector.looksLikeOtpText("八格驗證碼"))
        assertFalse(OtpFieldDetector.looksLikeOtpText("電子郵件"))
    }
}
