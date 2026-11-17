package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitOtpGroupDetectorTest {

    private fun cell(
        id: Int,
        left: Int,
        top: Int,
        width: Int = 48,
        height: Int = 56,
        maxLength: Int? = 1,
        textLength: Int = 0
    ): CellSnapshot {
        return CellSnapshot(
            id = id,
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            maxLength = maxLength,
            textLength = textLength,
            isEditable = true
        )
    }

    @Test
    fun detectsSameRowSixCells() {
        val cells = (0 until 6).map { i -> cell(i, left = 20 + i * 56, top = 200) }
        val ids = SplitOtpGroupDetector.detect(cells, 6, startId = 2)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ids)
    }

    @Test
    fun detectsWrappedTwoRows() {
        val row1 = (0 until 3).map { i -> cell(i, left = 40 + i * 60, top = 100) }
        val row2 = (0 until 3).map { i -> cell(i + 3, left = 40 + i * 60, top = 170) }
        val ids = SplitOtpGroupDetector.detect(row1 + row2, 6, startId = 0)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ids)
    }

    @Test
    fun allowsSlightVerticalOffsetOnSameRow() {
        val cells = listOf(
            cell(0, 10, 200),
            cell(1, 66, 208),
            cell(2, 122, 198),
            cell(3, 178, 205)
        )
        val ids = SplitOtpGroupDetector.detect(cells, 4, startId = 0)
        assertEquals(listOf(0, 1, 2, 3), ids)
    }

    @Test
    fun rejectsMixedLengthWideField() {
        val cells = listOf(
            cell(0, 10, 200),
            cell(1, 66, 200),
            cell(2, 122, 200),
            cell(3, 178, 200, width = 280, height = 56, maxLength = 20, textLength = 0)
        )
        assertNull(SplitOtpGroupDetector.detect(cells, 4, startId = 0))
    }

    @Test
    fun rejectsOrdinaryNumberFieldsThatAreNotSingleCells() {
        val phone = cell(0, 16, 300, width = 360, height = 56, maxLength = 11, textLength = 0)
        val amount = cell(1, 16, 380, width = 360, height = 56, maxLength = 8, textLength = 2)
        assertNull(SplitOtpGroupDetector.detect(listOf(phone, amount), 4, startId = 0))
        assertNull(SplitOtpGroupDetector.detect(listOf(phone, amount), 6, startId = 0))
    }

    @Test
    fun doesNotGuessWhenFewerCellsThanExpected() {
        val cells = (0 until 3).map { i -> cell(i, left = 20 + i * 56, top = 200) }
        assertNull(SplitOtpGroupDetector.detect(cells, 6, startId = 0))
    }

    @Test
    fun ignoresDistantUnrelatedBoxWhenSelectingExpectedCount() {
        val group = (0 until 6).map { i -> cell(i, left = 20 + i * 56, top = 200) }
        val leftover = cell(99, left = 20, top = 900, width = 48, height = 56)
        val ids = SplitOtpGroupDetector.detect(group + leftover, 6, startId = 0)
        assertNotNull(ids)
        assertEquals(6, ids!!.size)
        assertTrue(99 !in ids)
    }

    @Test
    fun readingOrderIsLeftToRightThenTopToBottom() {
        val cells = listOf(
            cell(2, 100, 180),
            cell(0, 20, 100),
            cell(3, 160, 180),
            cell(1, 80, 100)
        )
        val ordered = SplitOtpGroupDetector.readingOrder(cells).map { it.id }
        assertEquals(listOf(0, 1, 2, 3), ordered)
    }
}
