package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceLabelTest {

    @Test
    fun prefersAppLabelWhenTitleLooksLikeAMessageBody() {
        val source = SourceLabel.short(
            "訊息",
            "【銀行】您的驗證碼為 123456，請於5分鐘內完成驗證，切勿告知他人",
            "通知"
        )
        assertEquals("訊息", source)
        assertFalse(source.contains("123456"))
        assertFalse(source.contains("切勿告知"))
    }

    @Test
    fun combinesShortTitleWithAppLabel() {
        assertEquals("Gmail · Google", SourceLabel.short("Gmail", "Google", "通知"))
    }

    @Test
    fun fallsBackWhenBothMissing() {
        assertEquals("通知", SourceLabel.short(null, "   ", "通知"))
    }
}
