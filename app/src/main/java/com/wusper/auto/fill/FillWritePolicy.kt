package com.wusper.auto.fill

/**
 * Pure rules for what to write into an OTP field next.
 * Prevents a second paste/type after SET_TEXT already succeeded on
 * WebView fields whose text stays unreadable, which would otherwise
 * turn 123456 into 123456123456.
 */
enum class VisibleCodeState {
    UNREADABLE,
    EXACT,
    DOUBLED,
    OTHER
}

enum class FillWriteAction {
    STOP_SUCCESS,
    WRITE_SINGLE,
    FALLBACK_PASTE,
    FALLBACK_TYPE
}

object FillWritePolicy {

    fun visibleState(visibleText: String?, code: String): VisibleCodeState {
        val hay = FillTargetHeuristics.compact(visibleText)
        val want = FillTargetHeuristics.compact(code)
        if (want.isEmpty()) return VisibleCodeState.UNREADABLE
        if (hay.isEmpty()) return VisibleCodeState.UNREADABLE
        if (hay.equals(want, ignoreCase = true)) return VisibleCodeState.EXACT
        val doubled = want + want
        if (hay.equals(doubled, ignoreCase = true) ||
            hay.startsWith(doubled, ignoreCase = true)
        ) {
            return VisibleCodeState.DOUBLED
        }
        return VisibleCodeState.OTHER
    }

    fun beforeWrite(visibleText: String?, code: String): FillWriteAction {
        return when (visibleState(visibleText, code)) {
            VisibleCodeState.EXACT -> FillWriteAction.STOP_SUCCESS
            VisibleCodeState.DOUBLED -> FillWriteAction.WRITE_SINGLE
            VisibleCodeState.UNREADABLE,
            VisibleCodeState.OTHER -> FillWriteAction.WRITE_SINGLE
        }
    }

    /**
     * After ACTION_SET_TEXT was attempted with a single copy of [code].
     * If the action returned true and the field text is unreadable (common
     * in WebView), treat it as success and do not paste or type.
     */
    fun afterSetText(
        setTextReturned: Boolean,
        visibleText: String?,
        code: String
    ): FillWriteAction {
        return when (visibleState(visibleText, code)) {
            VisibleCodeState.EXACT -> FillWriteAction.STOP_SUCCESS
            VisibleCodeState.DOUBLED -> FillWriteAction.WRITE_SINGLE
            VisibleCodeState.UNREADABLE ->
                if (setTextReturned) FillWriteAction.STOP_SUCCESS
                else FillWriteAction.FALLBACK_PASTE
            VisibleCodeState.OTHER -> FillWriteAction.FALLBACK_PASTE
        }
    }

    fun afterPaste(
        pasteReturned: Boolean,
        visibleText: String?,
        code: String
    ): FillWriteAction {
        return when (visibleState(visibleText, code)) {
            VisibleCodeState.EXACT -> FillWriteAction.STOP_SUCCESS
            VisibleCodeState.DOUBLED -> FillWriteAction.WRITE_SINGLE
            VisibleCodeState.UNREADABLE ->
                if (pasteReturned) FillWriteAction.STOP_SUCCESS
                else FillWriteAction.FALLBACK_TYPE
            VisibleCodeState.OTHER -> FillWriteAction.FALLBACK_TYPE
        }
    }

    fun cellAlreadyHas(visibleText: String?, ch: String): Boolean {
        return visibleState(visibleText, ch) == VisibleCodeState.EXACT
    }

    fun afterSetTextCell(
        setTextReturned: Boolean,
        visibleText: String?,
        ch: String
    ): FillWriteAction {
        return afterSetText(setTextReturned, visibleText, ch)
    }
}
