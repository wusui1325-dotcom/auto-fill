package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpParserTest {

    @Test
    fun extractsTraditionalChineseSms() {
        assertEquals(
            "847291",
            OtpParser.extract("淘寶", "【淘寶】您的驗證碼為 847291，請於5分鐘內完成驗證")
        )
        assertEquals(
            "908712",
            OtpParser.extract(null, "短信驗證碼：908712（請勿泄露）")
        )
        assertEquals(
            "392841",
            OtpParser.extract("銀行", "您的動態密碼是 392841")
        )
    }

    @Test
    fun extractsSimplifiedChineseSms() {
        assertEquals(
            "123456",
            OtpParser.extract(null, "验证码123456，切勿告知他人")
        )
        assertEquals(
            "338291",
            OtpParser.extract("銀行", "【银行】尾号 6677 的验证码 338291，有效期 5 分钟")
        )
    }

    @Test
    fun extractsEnglishAndEmailNotifications() {
        assertEquals(
            "145889",
            OtpParser.extract("Gmail", "Your Google verification code is 145889")
        )
        assertEquals("482193", OtpParser.extract("Google", "G-482193"))
        assertEquals("220188", OtpParser.extract("Outlook", "Sign-in code: 220188"))
        assertEquals(
            "552211",
            OtpParser.extract(null, "Your code is 552211 for the number ending in 0891")
        )
    }

    @Test
    fun prefersOtpPhraseOverYear() {
        assertEquals(
            "553319",
            OtpParser.extract(null, "Your 2026 verification code is 553319")
        )
        assertEquals(
            "448821",
            OtpParser.extract(null, "2026年會員驗證碼 448821")
        )
        assertNull(OtpParser.extractFrom("Happy new year 2026"))
    }

    @Test
    fun prefersOtpPhraseOverPhoneTail() {
        assertEquals(
            "778899",
            OtpParser.extract(null, "尾號1234的手機驗證碼為 778899")
        )
        assertEquals(
            "338291",
            OtpParser.extract(null, "【銀行】尾號 6677 的驗證碼 338291，有效期 5 分鐘")
        )
    }

    @Test
    fun supportsFourToEightDigitsNearKeyword() {
        assertEquals("8821", OtpParser.extract(null, "OTP: 8821"))
        assertEquals("19223345", OtpParser.extract(null, "verification code 19223345"))
        assertEquals("847291", OtpParser.extract(null, "驗證碼 847 291"))
    }

    @Test
    fun ignoresMinutesAndPhoneNumbersWithoutOtpPhrase() {
        assertNull(OtpParser.extract(null, "請於5分鐘內回覆"))
        assertNull(OtpParser.extract(null, "您的手機號 13812345678 已註冊"))
        assertNull(OtpParser.extract(null, "Call 13812345678"))
        assertNull(OtpParser.extract(null, "訂單 20260311 已出貨"))
    }

    @Test
    fun acceptsIsolatedCodeInShortNotification() {
        assertEquals("847291", OtpParser.extract(null, "847291"))
        assertEquals("482193", OtpParser.extractFrom("G-482193"))
        assertNull(OtpParser.extractFrom("2026"))
        assertNull(OtpParser.extractFrom("1234"))
    }

    @Test
    fun prefersNearbyKeywordWhenSeveralNumbersExist() {
        assertEquals(
            "112233",
            OtpParser.extract(null, "訂單號 88219900 驗證碼 112233")
        )
    }

    @Test
    fun normalizesFullWidthDigitsAndColon() {
        assertEquals(
            "654321",
            OtpParser.extract(null, "驗證碼：６５４３２１")
        )
    }

    @Test
    fun usesBigTextOnlyWhenPrimaryHasNoCode() {
        assertEquals(
            "775511",
            OtpParser.extract("Gmail", "A sign-in code was sent", "Your verification code is 775511")
        )
        // Primary already has the code; big text dates should not override it.
        assertEquals(
            "775511",
            OtpParser.extract("Gmail", "verification code 775511", "Invoice 20260311 extra text")
        )
    }
}
