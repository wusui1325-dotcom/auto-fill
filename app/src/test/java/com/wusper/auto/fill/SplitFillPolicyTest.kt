package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitFillPolicyTest {

    @Test
    fun firstCellAcceptsFullCodeWhenMaxIsAtLeastCode() {
        assertTrue(SplitFillPolicy.firstCellAcceptsFullCode(8, 8))
        assertTrue(SplitFillPolicy.firstCellAcceptsFullCode(8, 6))
        assertFalse(SplitFillPolicy.firstCellAcceptsFullCode(1, 8))
        assertFalse(SplitFillPolicy.firstCellAcceptsFullCode(null, 8))
    }

    @Test
    fun groupStateDistributedOneCharEach() {
        val parts = listOf("A", "b", "1", "2", "C", "d", "3", "4")
        assertEquals(
            SplitGroupState.DISTRIBUTED,
            SplitFillPolicy.groupState(parts, "Ab12Cd34")
        )
    }

    @Test
    fun groupStateFirstHasAllNeedsPerCellSplit() {
        val parts = listOf("Ab12Cd34", "", "", "", "", "", "", "")
        assertEquals(
            SplitGroupState.FIRST_HAS_ALL,
            SplitFillPolicy.groupState(parts, "Ab12Cd34")
        )
        assertTrue(
            SplitFillPolicy.shouldClearFirstBeforePerCell(SplitGroupState.FIRST_HAS_ALL)
        )
    }

    @Test
    fun groupStateUnreadableWhenAllEmpty() {
        assertEquals(
            SplitGroupState.UNREADABLE,
            SplitFillPolicy.groupState(List(8) { "" }, "Ab12Cd34")
        )
    }
}
