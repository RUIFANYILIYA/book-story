package dev.ebookd.core.text

import kotlin.math.roundToInt

/**
 * Finds the place in a book that a percentage points at.
 *
 * This is the other half of the anchor's bargain. A character offset is only meaningful against the
 * file it was measured in, so when the file is replaced — a re-upload, a corrected edition — the
 * stored offset has to be thrown away and the position re-derived from the one number that
 * survived: how far through the reader was. The chapter starts are the document's own map, so the
 * answer needs no layout and no font: it is a property of the text, and it lands on the same
 * paragraph whatever the reader's settings.
 *
 * [starts] is each chapter's offset in the normalized document, ascending; [total] is that
 * document's length in characters.
 */
object PercentAnchor {

    fun derive(
        starts: IntArray,
        total: Int,
        percent: Double,
    ): Pair<Int, Int>? {
        if (starts.isEmpty() || total <= 0) return null
        val target = ((percent.coerceIn(0.0, 100.0) / 100.0) * total)
            .roundToInt()
            .coerceIn(0, total - 1)

        // The last chapter that begins at or before the target. Binary search rather than a scan
        // because a serialised novel has thousands of chapters and this runs on the open path.
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= target) low = mid else high = mid - 1
        }
        return low to (target - starts[low]).coerceAtLeast(0)
    }
}
