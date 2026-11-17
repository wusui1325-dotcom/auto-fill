package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpParserTest {

    @Test
    fun extractsTraditionalChineseSms() {
        assertEquals("847291", OtpParser.extract("淘寶", "【淘寶】您的驗證碼為 847291，請於5分鐘內完成驗證"))
        assertEquals("908712", OtpParser.extract(null, "短信驗證碼：908712（請勿泄露）"))
        assertEquals("392841", OtpParser.extract("銀行", "您的動態密碼是 392841"))
        assertEquals("776655", OtpParser.extract(null, "動態口令 776655，10分鐘內有效"))
        assertEquals("112244", OtpParser.extract(null, "手機驗證碼為112244"))
    }

    @Test
    fun extractsSimplifiedChineseSms() {
        assertEquals("123456", OtpParser.extract(null, "验证码123456，切勿告知他人"))
        assertEquals("338291", OtpParser.extract("銀行", "【银行】尾号 6677 的验证码 338291，有效期 5 分钟"))
        assertEquals("445566", OtpParser.extract(null, "【阿里云】验证码：445566，用于登录，5分钟内有效"))
        assertEquals("998877", OtpParser.extract(null, "您的校验码是 998877"))
        assertEquals("334455", OtpParser.extract(null, "临时密码 334455，请勿转发"))
    }

    @Test
    fun extractsEnglishAndEmailNotifications() {
        assertEquals("145889", OtpParser.extract("Gmail", "Your Google verification code is 145889"))
        assertEquals("482193", OtpParser.extract("Google", "G-482193"))
        assertEquals("220188", OtpParser.extract("Outlook", "Sign-in code: 220188"))
        assertEquals("552211", OtpParser.extract(null, "Your code is 552211 for the number ending in 0891"))
        assertEquals("667788", OtpParser.extract(null, "Use 667788 as your security code"))
        assertEquals("102938", OtpParser.extract(null, "102938 is your Apple ID code"))
        assertEquals("564738", OtpParser.extract(null, "Here is your OTP: 564738"))
        assertEquals("246810", OtpParser.extract(null, "Enter code: 246810"))
        assertEquals("135790", OtpParser.extract("Amazon", "135790 is your Amazon OTP"))
    }

    @Test
    fun readsBigTextInboxAndMessageParts() {
        assertEquals(
            "775511",
            OtpParser.extract(
                NotificationParts(
                    title = "Gmail",
                    text = "A sign-in code was sent",
                    extraChunks = listOf("Your verification code is 775511")
                )
            )
        )
        assertEquals(
            "775511",
            OtpParser.extract(
                NotificationParts(
                    title = "Gmail",
                    text = "verification code 775511",
                    extraChunks = listOf("Invoice 20260311 extra text")
                )
            )
        )
        assertEquals(
            "889900",
            OtpParser.extract(
                NotificationParts(
                    title = "訊息",
                    text = "2則新訊息",
                    extraChunks = listOf("昨天的對帳單已到期"),
                    messages = listOf("晚餐一起吃嗎", "您的驗證碼為 889900")
                )
            )
        )
        assertEquals(
            "664422",
            OtpParser.extract(
                NotificationParts(
                    title = "Email",
                    text = "New message",
                    extraChunks = listOf("Yesterday you spent 1288", "verification code 664422")
                )
            )
        )
    }

    @Test
    fun prefersOtpPhraseOverYearAndDate() {
        assertEquals("553319", OtpParser.extract(null, "Your 2026 verification code is 553319"))
        assertEquals("448821", OtpParser.extract(null, "2026年會員驗證碼 448821"))
        assertEquals("445566", OtpParser.extract(null, "有效期至 2026-12-31，驗證碼 445566"))
        assertNull(OtpParser.extractFrom("Happy new year 2026"))
        assertNull(OtpParser.extractFrom("日期 20260311 已更新"))
    }

    @Test
    fun prefersOtpPhraseOverPhoneTail() {
        assertEquals("778899", OtpParser.extract(null, "尾號1234的手機驗證碼為 778899"))
        assertEquals("338291", OtpParser.extract(null, "【銀行】尾號 6677 的驗證碼 338291，有效期 5 分鐘"))
    }

    @Test
    fun prefersOtpOverOrderAmountAndTracking() {
        assertEquals("112233", OtpParser.extract(null, "訂單號 88219900 驗證碼 112233"))
        assertEquals("556677", OtpParser.extract(null, "订单号：2026031188 验证码：556677"))
        assertEquals("221133", OtpParser.extract(null, "金額 1288 元，驗證碼 221133"))
        assertNull(OtpParser.extract(null, "訂單 20260311 已出貨"))
        assertNull(OtpParser.extract(null, "運單號 88990077 已發貨"))
        assertNull(OtpParser.extract(null, "Your tracking number is 88990077"))
        assertNull(OtpParser.extract(null, "Invoice 445566 already paid"))
        assertNull(OtpParser.extract(null, "合計 128800 已付款"))
        assertNull(OtpParser.extract(null, "Amount \$123456 was charged"))
    }

    @Test
    fun supportsGroupedDigitsAndFourToEightLength() {
        assertEquals("8821", OtpParser.extract(null, "OTP: 8821"))
        assertEquals("19223345", OtpParser.extract(null, "verification code 19223345"))
        assertEquals("847291", OtpParser.extract(null, "驗證碼 847 291"))
        assertEquals("847291", OtpParser.extract(null, "驗證碼 847-291"))
        assertEquals("123456", OtpParser.extract(null, "your code is 12 34 56"))
        assertEquals("12345678", OtpParser.extract(null, "OTP: 1234-5678"))
    }

    @Test
    fun extractsLabeledAlphanumericCodes() {
        assertEquals("A1B2C3", OtpParser.extract(null, "Your verification code is A1B2C3"))
        assertEquals("X3K9P2", OtpParser.extract(null, "驗證碼：X3K9P2，請於10分鐘內使用"))
        assertEquals("ab12CD", OtpParser.extract(null, "OTP: ab12CD"))
        assertNull(OtpParser.extractFrom("Meeting in room A1B2C3"))
        assertNull(OtpParser.extractFrom("Hello ANDROID"))
    }

    @Test
    fun ignoresMinutesPhonesAndUnrelatedNumbers() {
        assertNull(OtpParser.extract(null, "請於5分鐘內回覆"))
        assertNull(OtpParser.extract(null, "您的手機號 13812345678 已註冊"))
        assertNull(OtpParser.extract(null, "Call 13812345678"))
        assertNull(OtpParser.extract(null, "zip code: 94107"))
        assertNull(OtpParser.extract(null, "error code: 50312"))
        assertNull(OtpParser.extract(null, "postal code 10001"))
    }

    @Test
    fun acceptsIsolatedCodeInShortNotification() {
        assertEquals("847291", OtpParser.extract(null, "847291"))
        assertEquals("482193", OtpParser.extractFrom("G-482193")?.code)
        assertNull(OtpParser.extractFrom("2026"))
        assertNull(OtpParser.extractFrom("1234"))
        assertNull(OtpParser.extractFrom("261117")) // looks like YYMMDD
    }

    @Test
    fun doesNotUseIsolatedGuessWhenTheBodyIsLong() {
        assertNull(
            OtpParser.extract(
                NotificationParts(
                    title = "Newsletter",
                    text = "Weekly digest",
                    extraChunks = listOf("A very long email body that happens to mention 847291 among other numbers 20260311 and 88990077")
                )
            )
        )
    }

    @Test
    fun normalizesFullWidthDigitsLettersAndDashes() {
        assertEquals("654321", OtpParser.extract(null, "驗證碼：６５４３２１"))
        assertEquals("A1B2C3", OtpParser.extract(null, "OTP：Ａ１Ｂ２Ｃ３"))
        assertEquals("847291", OtpParser.extract(null, "驗證碼 847－291"))
    }
}
