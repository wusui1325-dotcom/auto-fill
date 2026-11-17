package com.wusper.auto.fill

/**
 * Detects a group of one-character OTP cells from synthetic layout snapshots.
 * Does not click-guess unrelated fields; the caller maps ids back to real nodes.
 */
data class CellSnapshot(
    val id: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val maxLength: Int? = null,
    val textLength: Int = 0,
    val isEditable: Boolean = true,
    val isPassword: Boolean = false,
    val className: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val autofillHints: List<String> = emptyList()
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

object SplitOtpGroupDetector {

    fun detect(cells: List<CellSnapshot>, expected: Int, startId: Int? = null): List<Int>? {
        if (expected !in 4..8) return null
        val start = startId?.let { id -> cells.firstOrNull { it.id == id } }
        val compactRef = cells.firstOrNull {
            looksLikeCompactCell(it, expected) && (start == null || it.id == start.id || sameRow(it, start))
        } ?: start

        val candidates = cells.filter {
            looksLikeCell(it, compactRef, expected) || isSameRowPasteFirst(it, compactRef, expected)
        }
        if (candidates.size < expected) return null

        val sizeRef = compactRef ?: start
        val scoped = if (sizeRef != null) {
            val nearby = candidates.filter { nearStart(it, sizeRef, expected) || it.id == start?.id }
            if (nearby.size >= expected) nearby else candidates
        } else {
            candidates
        }
        if (scoped.size < expected) return null

        val cluster = pickCluster(scoped, expected, start ?: compactRef) ?: return null
        if (!isCoherentGroup(cluster, expected)) return null
        return readingOrder(cluster).map { it.id }
    }

    internal fun looksLikeCompactCell(cell: CellSnapshot, expected: Int = 8): Boolean {
        if (!cell.isEditable && !cell.isPassword) return false
        if (cell.textLength >= 2) return false
        val max = cell.maxLength
        if (max != null && max > 1) return false
        if (cell.width <= 0 || cell.height <= 0) return false
        if (cell.width > cell.height * 3 && cell.width > 160) return false
        return true
    }

    private fun isSameRowPasteFirst(
        cell: CellSnapshot,
        reference: CellSnapshot?,
        expected: Int
    ): Boolean {
        if (reference == null) return false
        if (!cell.isEditable && !cell.isPassword) return false
        if (cell.textLength >= 2) return false
        val max = cell.maxLength
        if (max != null && max > expected) return false
        if (max != null && max in 1 until expected) return false
        if (!sameRow(cell, reference)) return false
        // Heights should still look like the same row of boxes.
        if (reference.height > 0) {
            val hOk = cell.height in (reference.height / 2)..(reference.height * 2).coerceAtLeast(reference.height + 8)
            if (!hOk) return false
        }
        return true
    }

    private fun sameRow(a: CellSnapshot, b: CellSnapshot): Boolean {
        val band = maxOf(a.height, b.height, 24) * 0.65
        return kotlin.math.abs(a.centerY - b.centerY) <= band ||
            verticalOverlap(a, b) >= minOf(a.height, b.height) / 2
    }

    /**
     * A compact box is a cell. maxlength 1 is the usual case; the first box is
     * sometimes given maxlength equal to the full code so a paste can spread.
     * Wide maxlength fields (phone, amount) stay out.
     */
    internal fun looksLikeCell(
        cell: CellSnapshot,
        reference: CellSnapshot? = null,
        expected: Int = 8
    ): Boolean {
        if (!cell.isEditable && !cell.isPassword) return false
        val max = cell.maxLength
        if (max != null && max > expected) return false
        if (cell.textLength >= 2) return false
        if (max != null && max <= 1) return true
        if (max != null && max in 2..expected && !isCellSized(cell, reference)) return false
        return cell.textLength <= 1 && isCellSized(cell, reference)
    }

    private fun isCellSized(cell: CellSnapshot, reference: CellSnapshot?): Boolean {
        if (cell.width <= 0 || cell.height <= 0) return false
        // Reject wide input bars that are clearly not a single character box.
        if (cell.width > cell.height * 3 && cell.width > 160) return false
        if (reference != null) return similarSize(cell, reference)
        // Unreferenced: allow compact, roughly square-to-modest boxes.
        return cell.width <= cell.height * 3
    }

    private fun similarSize(a: CellSnapshot, b: CellSnapshot): Boolean {
        if (b.width <= 0 || b.height <= 0) return false
        val wOk = a.width in (b.width / 2)..(b.width * 2).coerceAtLeast(b.width + 8)
        val hOk = a.height in (b.height / 2)..(b.height * 2).coerceAtLeast(b.height + 8)
        return wOk && hOk
    }

    private fun nearStart(cell: CellSnapshot, start: CellSnapshot, expected: Int): Boolean {
        if (!similarSize(cell, start)) return false
        val maxW = start.width.coerceAtLeast(24) * (expected + 2)
        val maxH = start.height.coerceAtLeast(24) * (rowCountHint(expected) + 2)
        return kotlin.math.abs(cell.centerX - start.centerX) <= maxW &&
            kotlin.math.abs(cell.centerY - start.centerY) <= maxH
    }

    private fun rowCountHint(expected: Int): Int = when {
        expected <= 4 -> 2
        expected <= 6 -> 3
        else -> 4
    }

    private fun pickCluster(
        cells: List<CellSnapshot>,
        expected: Int,
        start: CellSnapshot?
    ): List<CellSnapshot>? {
        if (cells.size == expected) return cells
        val seed = start ?: cells.minBy { it.top * 10_000 + it.left }
        val ranked = cells.sortedBy { distance(it, seed) }
        val take = ranked.take(expected)
        if (take.size != expected) return null
        // The expected-th neighbour should still be in the same visual group, not a distant leftover.
        val last = take.last()
        val typical = median(take.map { it.width.coerceAtLeast(1) })
        val far = distance(last, seed) > typical * (expected * 2 + 4)
        return if (far) null else take
    }

    private fun isCoherentGroup(cells: List<CellSnapshot>, expected: Int): Boolean {
        if (cells.size != expected) return false
        val widths = cells.map { it.width }
        val heights = cells.map { it.height }
        val typicalW = median(widths)
        val typicalH = median(heights)
        if (typicalW <= 0 || typicalH <= 0) return false
        val widthRange = (typicalW / 2)..(typicalW * 2).coerceAtLeast(typicalW + 8)
        val oddWidths = cells.filter { it.width !in widthRange }
        if (oddWidths.size > 1) return false
        if (oddWidths.size == 1) {
            val wide = oddWidths.first()
            val leftmost = cells.minBy { it.left }
            if (wide.id != leftmost.id) return false
        }
        if (heights.any { it !in (typicalH / 2)..(typicalH * 2).coerceAtLeast(typicalH + 8) }) return false
        val rows = clusterRows(cells)
        if (rows.isEmpty()) return false
        val total = rows.sumOf { it.size }
        if (total != expected) return false
        // Scattered one-per-row boxes that are not a wrapped grid are too ambiguous.
        if (rows.size > 1) {
            val minRow = rows.minOf { it.size }
            val maxRow = rows.maxOf { it.size }
            if (minRow < 2 && expected > 4) return false
            if (maxRow - minRow > 2) return false
        }
        return true
    }

    internal fun readingOrder(cells: List<CellSnapshot>): List<CellSnapshot> {
        return clusterRows(cells).flatten()
    }

    internal fun clusterRows(cells: List<CellSnapshot>): List<List<CellSnapshot>> {
        if (cells.isEmpty()) return emptyList()
        val remaining = cells.sortedBy { it.centerY }.toMutableList()
        val rows = mutableListOf<List<CellSnapshot>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val band = maxOf(seed.height, 24) * 0.65
            val row = mutableListOf(seed)
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val next = iter.next()
                val sameRow = kotlin.math.abs(next.centerY - seed.centerY) <= band ||
                    verticalOverlap(seed, next) >= minOf(seed.height, next.height) / 2
                if (sameRow) {
                    row += next
                    iter.remove()
                }
            }
            rows += row.sortedBy { it.left }
        }
        return rows
    }

    private fun verticalOverlap(a: CellSnapshot, b: CellSnapshot): Int {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        return (bottom - top).coerceAtLeast(0)
    }

    private fun distance(a: CellSnapshot, b: CellSnapshot): Int {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        return kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
