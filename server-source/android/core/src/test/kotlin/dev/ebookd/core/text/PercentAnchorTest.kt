package dev.ebookd.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rescue path for a replaced file, pinned.
 *
 * This runs exactly when the stored anchor has been invalidated — the book was re-uploaded, or read
 * on another device in a different edition — and the reader's only surviving coordinate is the
 * percentage. It is also the code path least likely to be exercised by hand, which is why the edges
 * (0 %, 100 %, a chapter list that starts after zero) are all here.
 */
class PercentAnchorTest {

    // Four chapters of 1 000 characters each: 0–999, 1 000–1 999, 2 000–2 999, 3 000–3 999.
    private val starts = intArrayOf(0, 1_000, 2_000, 3_000)
    private val total = 4_000

    @Test
    fun `a percentage lands in the chapter that contains it`() {
        assertEquals(0 to 0, PercentAnchor.derive(starts, total, 0.0))
        assertEquals(0 to 250, PercentAnchor.derive(starts, total, 6.25))
        assertEquals(1 to 0, PercentAnchor.derive(starts, total, 25.0))
        assertEquals(2 to 500, PercentAnchor.derive(starts, total, 62.5))
        assertEquals(3 to 999, PercentAnchor.derive(starts, total, 99.975))
    }

    @Test
    fun `the last character of the book is where 100 percent points`() {
        // Not past the end: an offset equal to the length is a valid "saturating" request for the
        // last page elsewhere, but a resume that points one past the text would make the layout ask
        // the document for a character that is not there.
        assertEquals(3 to 999, PercentAnchor.derive(starts, total, 100.0))
        assertEquals(3 to 999, PercentAnchor.derive(starts, total, 140.0))
    }

    @Test
    fun `a chapter list that starts after zero still resolves`() {
        // The first heading of a novel is often not at offset 0 (a title page precedes it). The
        // search must not assume `starts[0] == 0`.
        val offset = intArrayOf(40, 1_040, 2_040)
        // A percentage that falls before the first heading resolves *into* that chapter, at the
        // offset it implies — not to offset 0 of chapter 1, which does not start here either.
        assertEquals(0 to 0, PercentAnchor.derive(offset, 3_000, 0.0))
        assertEquals(0 to 20, PercentAnchor.derive(offset, 3_000, 2.0))
    }

    @Test
    fun `nothing to resolve against is not a position at all`() {
        // A book with no chapters, or a text that normalised to nothing: the caller opens at the
        // start rather than at an arbitrary offset that happens to be arithmetic.
        assertNull(PercentAnchor.derive(IntArray(0), total, 50.0))
        assertNull(PercentAnchor.derive(starts, 0, 50.0))
    }

    @Test
    fun `a percentage between two chapters takes the earlier one`() {
        // Exact boundaries belong to the chapter that starts there: 25 % is the first character of
        // chapter 2, not the last of chapter 1.
        assertEquals(1 to 0, PercentAnchor.derive(starts, total, 25.0))
        assertEquals(3 to 0, PercentAnchor.derive(starts, total, 75.0))
    }
}
