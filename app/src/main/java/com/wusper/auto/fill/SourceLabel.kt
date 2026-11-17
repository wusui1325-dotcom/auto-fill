package com.wusper.auto.fill

/** Short source for the confirm UI: app label and/or a short title, never a full message body. */
object SourceLabel {
    fun short(appLabel: String?, title: String?, fallback: String): String {
        val label = appLabel?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        return when {
            t.isBlank() -> label.ifBlank { fallback }
            looksLikeBody(t) -> label.ifBlank { fallback }
            label.isBlank() -> truncate(t)
            t.equals(label, ignoreCase = true) -> label
            else -> "$label · ${truncate(t)}"
        }
    }

    private fun looksLikeBody(title: String): Boolean {
        if (title.length > 28) return true
        if (title.contains('\n')) return true
        return false
    }

    private fun truncate(s: String, max: Int = 28): String {
        return if (s.length <= max) s else s.take(max - 1) + "…"
    }
}
