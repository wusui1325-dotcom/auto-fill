package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillWritePolicyTest {

    @Test
    fun alreadyHasExactCodeStopsWithoutWriting() {
        assertEquals(VisibleCodeState.EXACT, FillWritePolicy.visibleState("123456", "123456"))
        assertEquals(VisibleCodeState.EXACT, FillWritePolicy.visibleState("12 34 56", "123456"))
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.beforeWrite("123456", "123456")
        )
    }

    @Test
    fun doubledCodeIsDetectedAndRewrittenToSingleCopy() {
        assertEquals(VisibleCodeState.DOUBLED, FillWritePolicy.visibleState("123456123456", "123456"))
        assertEquals(VisibleCodeState.DOUBLED, FillWritePolicy.visibleState("12345612345699", "123456"))
        assertEquals(
            FillWriteAction.WRITE_SINGLE,
            FillWritePolicy.beforeWrite("123456123456", "123456")
        )
        assertEquals(
            FillWriteAction.WRITE_SINGLE,
            FillWritePolicy.afterSetText(true, "123456123456", "123456")
        )
    }

    @Test
    fun setTextTrueButUnreadableStopsAndDoesNotPaste() {
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.afterSetText(true, "", "123456")
        )
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.afterSetText(true, null, "123456")
        )
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.afterSetText(true, "  ", "123456")
        )
    }

    @Test
    fun setTextTrueWithExactVisibleTextStops() {
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.afterSetText(true, "123456", "123456")
        )
    }

    @Test
    fun setTextFalseOrWrongVisibleTextFallsBackToPaste() {
        assertEquals(
            FillWriteAction.FALLBACK_PASTE,
            FillWritePolicy.afterSetText(false, "", "123456")
        )
        assertEquals(
            FillWriteAction.FALLBACK_PASTE,
            FillWritePolicy.afterSetText(false, null, "123456")
        )
        assertEquals(
            FillWriteAction.FALLBACK_PASTE,
            FillWritePolicy.afterSetText(true, "000000", "123456")
        )
        assertEquals(
            FillWriteAction.FALLBACK_PASTE,
            FillWritePolicy.afterSetText(false, "000000", "123456")
        )
    }

    @Test
    fun afterPasteUnreadableSuccessDoesNotType() {
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.afterPaste(true, "", "123456")
        )
        assertEquals(
            FillWriteAction.FALLBACK_TYPE,
            FillWritePolicy.afterPaste(false, "", "123456")
        )
        assertEquals(
            FillWriteAction.FALLBACK_TYPE,
            FillWritePolicy.afterPaste(true, "000000", "123456")
        )
    }

    @Test
    fun cellAlreadyHasDigitSkipsAnotherWrite() {
        assertTrue(FillWritePolicy.cellAlreadyHas("1", "1"))
        assertFalse(FillWritePolicy.cellAlreadyHas("", "1"))
        assertFalse(FillWritePolicy.cellAlreadyHas("2", "1"))
        assertEquals(
            FillWriteAction.STOP_SUCCESS,
            FillWritePolicy.afterSetTextCell(true, "", "1")
        )
        assertEquals(
            FillWriteAction.FALLBACK_PASTE,
            FillWritePolicy.afterSetTextCell(false, "", "1")
        )
    }

    @Test
    fun containsAloneIsNotTreatedAsExactWhenTheFieldIsDoubled() {
        assertTrue(FillTargetHeuristics.codeFullyPresent("123456123456", "123456"))
        assertEquals(VisibleCodeState.DOUBLED, FillWritePolicy.visibleState("123456123456", "123456"))
        assertEquals(
            FillWriteAction.WRITE_SINGLE,
            FillWritePolicy.afterSetText(true, "123456123456", "123456")
        )
    }
}
