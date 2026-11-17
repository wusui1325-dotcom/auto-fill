package com.wusper.auto.fill

/**
 * Rules for writing an OTP into a row of cells, including the common
 * "paste the whole code into the first box and let it spread" pattern.
 */
enum class SplitGroupState {
    DISTRIBUTED,
    FIRST_HAS_ALL,
    UNREADABLE,
    OTHER
}

object SplitFillPolicy {

    fun firstCellAcceptsFullCode(maxLength: Int?, codeLength: Int): Boolean {
        if (codeLength <= 0) return false
        return maxLength != null && maxLength >= codeLength
    }

    fun groupState(cellTexts: List<String?>, code: String): SplitGroupState {
        val want = FillTargetHeuristics.compact(code)
        if (want.isEmpty() || cellTexts.isEmpty()) return SplitGroupState.UNREADABLE
        val parts = cellTexts.map { FillTargetHeuristics.compact(it) }
        if (parts.size == want.length &&
            parts.indices.all { parts[it].equals(want[it].toString(), ignoreCase = true) }
        ) {
            return SplitGroupState.DISTRIBUTED
        }
        val filled = parts.count { it.isNotEmpty() }
        val joined = parts.joinToString("")
        if (joined.equals(want, ignoreCase = true) && filled > 1) {
            return SplitGroupState.DISTRIBUTED
        }
        if (parts[0].equals(want, ignoreCase = true) && parts.drop(1).all { it.isEmpty() }) {
            return SplitGroupState.FIRST_HAS_ALL
        }
        if (parts.all { it.isEmpty() }) return SplitGroupState.UNREADABLE
        return SplitGroupState.OTHER
    }

    fun shouldClearFirstBeforePerCell(state: SplitGroupState): Boolean {
        return state == SplitGroupState.FIRST_HAS_ALL || state == SplitGroupState.OTHER
    }
}
