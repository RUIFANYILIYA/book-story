package dev.ebookd.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which queued shelf statuses survive an attempt.
 *
 * This is the decision that decides whether the queue is a feature or a leak. A reader who marks
 * thirty books on a train wants them filed when they resurface; a reader whose account cannot write
 * shared metadata does not want thirty requests replayed at the server on every launch forever, and
 * neither of those is served by a rule that treats "not there" and "said no" as the same answer.
 */
class ShelfEditQueueTest {

    private fun report(
        applied: List<String> = emptyList(),
        failed: List<String> = emptyList(),
        failure: BatchFailure = BatchFailure.NONE,
        errors: Map<String, String> = emptyMap(),
    ) = BatchReport(applied, failed, failure, errors)

    private val three = listOf("a", "b", "c")

    @Test
    fun `a server that was not there keeps every choice the reader made`() {
        val owed = ShelfEditQueue.stillOwed(three, report(failed = three, failure = BatchFailure.OFFLINE))
        assertEquals(three, owed)
    }

    @Test
    fun `a server that refused is not going to change its mind on the next sync`() {
        assertTrue(ShelfEditQueue.stillOwed(three, report(failed = three, failure = BatchFailure.FORBIDDEN)).isEmpty())
    }

    @Test
    fun `a book someone deleted in the meantime is forgotten, and its neighbours are not`() {
        val owed = ShelfEditQueue.stillOwed(
            three,
            report(
                applied = listOf("a"),
                failed = listOf("b", "c"),
                errors = mapOf("b" to "book_not_found", "c" to "collection_not_found"),
            ),
        )
        assertEquals(listOf("c"), owed)
    }

    @Test
    fun `an accepted change is never owed again`() {
        assertTrue(ShelfEditQueue.stillOwed(three, report(applied = three)).isEmpty())
    }

    @Test
    fun `a failure nobody modelled stays queued, because retrying is cheaper than losing it`() {
        val owed = ShelfEditQueue.stillOwed(three, report(failed = three, failure = BatchFailure.OTHER))
        assertEquals(three, owed)
    }

    @Test
    fun `an id that was not part of this attempt cannot become owed by it`() {
        // The queue holds more than one batch's worth of rows; a report is only ever about the ids it
        // was sent, and an answer that names another book must not resurrect or retire it.
        val owed = ShelfEditQueue.stillOwed(listOf("a"), report(failed = listOf("a", "ghost"), failure = BatchFailure.OFFLINE))
        assertEquals(listOf("a"), owed)
    }
}
