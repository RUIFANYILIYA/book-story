package dev.ebookd.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one judgement the batch screen makes: which of *my* ids the server actually changed.
 *
 * Everything else about a batch is a network call. This decides what the app is allowed to reflect
 * locally, and the interesting case is the one that never appears in a happy path — a response that
 * does not mention an id. Reading silence as success would leave a book on the glass marked 已读
 * that no server ever agreed to mark, and the reader would find out on another device.
 */
class BatchResultSplitTest {

    private fun result(vararg changes: Pair<String, Boolean>) = BatchResultDto(
        requested = changes.size,
        changed = changes.map { (id, ok) -> BatchChangeDto(id = id, ok = ok, error = if (ok) null else "book_not_found") },
        applied = changes.count { it.second },
        skipped = changes.count { !it.second },
    )

    @Test
    fun `every id accepted means the whole selection took the change`() {
        val split = result("a" to true, "b" to true).split(listOf("a", "b"))
        assertEquals(listOf("a", "b"), split.applied)
        assertTrue(split.allApplied)
    }

    @Test
    fun `a book someone else deleted in the meantime is reported per id, not as a failed batch`() {
        val split = result("a" to true, "b" to false).split(listOf("a", "b"))
        assertEquals(listOf("a"), split.applied)
        assertEquals(listOf("b"), split.failed)
        assertFalse(split.allApplied)
    }

    @Test
    fun `an id the answer does not mention counts as not done`() {
        val split = result("a" to true).split(listOf("a", "b"))
        assertEquals(listOf("a"), split.applied)
        assertEquals(listOf("b"), split.failed)
    }

    @Test
    fun `an id the answer mentions that was never selected is ignored`() {
        // Cannot happen against this server, and that is the reason the check is here rather than
        // absent: an id nobody asked for must not be written into the local cache, because the
        // caller's `applied` list is what Room is updated with.
        val split = result("a" to true, "ghost" to true).split(listOf("a"))
        assertEquals(listOf("a"), split.applied)
        assertTrue(split.failed.isEmpty())
    }

    @Test
    fun `the order of the selection is the order of the answer`() {
        val split = result("b" to true, "a" to true).split(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b"), split.applied)
        assertEquals(listOf("c"), split.failed)
    }

    @Test
    fun `an empty answer is a refused batch, not a vacuous success`() {
        val split = BatchResultDto(requested = 2).split(listOf("a", "b"))
        assertTrue(split.applied.isEmpty())
        assertEquals(2, split.failed.size)
        assertFalse(split.allApplied)
    }
}
