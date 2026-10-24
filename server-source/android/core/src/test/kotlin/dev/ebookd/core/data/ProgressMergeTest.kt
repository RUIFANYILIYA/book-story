package dev.ebookd.core.data

import dev.ebookd.core.db.ReadingPositionEntity
import dev.ebookd.core.net.SyncDeltaItemDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one judgement in the sync, pinned.
 *
 * Everything else in [SyncRepository] is plumbing that a device test covers; this is the decision
 * that two devices can disagree about, and a wrong answer here is a reader losing their place
 * silently — the failure mode with no error message and no way to notice until they open the book.
 */
class ProgressMergeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun local(
        percent: Double,
        baseRev: Long = 4L,
    ) = ReadingPositionEntity(
        bookId = "book-1",
        chapterIndex = 3,
        charOffset = 120,
        updatedAt = 0L,
        percent = percent,
        fileId = "file-a",
        sha256 = "sha-a",
        baseRev = baseRev,
    )

    private fun remote(
        rev: Long,
        percent: Double?,
        chapterIndex: Int? = 9,
        charOffset: Int? = 40,
    ) = RemotePosition(
        rev = rev,
        percent = percent,
        chapterIndex = chapterIndex,
        charOffset = charOffset,
        fileId = "file-b",
        sha256 = "sha-b",
    )

    @Test
    fun `a book this device has never opened adopts the server's position`() {
        val merged = ProgressMerge.merge(local = null, remote = remote(rev = 7, percent = 12.5), bookId = "book-1")
        requireNotNull(merged)
        assertEquals(9, merged.chapterIndex)
        assertEquals(40, merged.charOffset)
        assertEquals(12.5, merged.percent, 0.0)
        // The revision is kept, so the next push offers it as its base rather than the zero the row
        // would otherwise carry — which the server would read as "based on nothing" and could answer
        // with a conflict for a position nobody is arguing about.
        assertEquals(7L, merged.baseRev)
    }

    @Test
    fun `the further reading wins even when the server wrote later`() {
        // The server's copy is newer by revision and *behind* by content: the other device wrote
        // after we did but had read less. Revision orders writes; only percentage orders reading.
        val merged = ProgressMerge.merge(local = local(percent = 62.0), remote = remote(rev = 9, percent = 31.0), bookId = "book-1")
        assertNull(merged)
        val theOther = ProgressMerge.merge(local = local(percent = 31.0), remote = remote(rev = 9, percent = 62.0), bookId = "book-1")
        requireNotNull(theOther)
        assertEquals(62.0, theOther.percent, 0.0)
    }

    @Test
    fun `our own write coming back through the delta is not a merge`() {
        // Every push lands in the delta stream too. Adopting our own echo would replace the anchor
        // with the one we sent and re-stamp `updatedAt`, which is a write that changes nothing.
        val merged = ProgressMerge.merge(
            local = local(percent = 40.0, baseRev = 12L),
            remote = remote(rev = 12, percent = 40.0),
            bookId = "book-1",
        )
        assertNull(merged)
    }

    @Test
    fun `a server row with no percentage cannot outrank a place the reader is in`() {
        // A status-only change (read/finished) carries no position. There is nothing in it to
        // compare, and "somebody marked this read" must not teleport a reader who is halfway in.
        assertNull(ProgressMerge.merge(local = local(percent = 5.0), remote = remote(rev = 20, percent = null), bookId = "book-1"))
    }

    @Test
    fun `a position from a different file version keeps the percentage and drops the anchor`() {
        // Cannot happen through this function — it adopts both — and that is the point of the check
        // below: what the merge stores is the server's anchor *and* its sha, so the open path can
        // tell the difference and re-derive from the percentage instead of trusting the offset.
        val merged = ProgressMerge.merge(local = null, remote = remote(rev = 3, percent = 77.0), bookId = "book-1")
        requireNotNull(merged)
        assertEquals("sha-b", merged.sha256)
        assertEquals("file-b", merged.fileId)
    }

    @Test
    fun `a local position that wins is rebased onto the server's revision`() {
        val mine = local(percent = 85.0, baseRev = 17L)
        val theirs = remote(rev = 18, percent = 80.0)
        // The local value stands — it is further along — so there is nothing to adopt…
        assertNull(ProgressMerge.merge(mine, theirs, bookId = "book-1"))
        // …but the row must not go on offering a revision the server has left behind: the change
        // that would have rebased it is the one that just lost, and it is now behind the cursor.
        // Every push from that state is refused as a conflict, for good.
        val rebased = requireNotNull(ProgressMerge.rebase(mine, theirs))
        assertEquals(18L, rebased.baseRev)
        // The position itself is untouched — this is a revision being recorded, not a move.
        assertEquals(85.0, rebased.percent, 0.0)
        assertEquals(3, rebased.chapterIndex)
        assertEquals(120, rebased.charOffset)
    }

    @Test
    fun `an echo is neither merged nor rebased`() {
        // Our own write arrives through the delta with exactly the revision we built on. Both
        // decisions read `rev <= baseRev` as "nothing happened here".
        val mine = local(percent = 40.0, baseRev = 12L)
        val echo = remote(rev = 12, percent = 40.0)
        assertNull(ProgressMerge.merge(mine, echo, bookId = "book-1"))
        assertNull(ProgressMerge.rebase(mine, echo))
    }

    @Test
    fun `a malformed anchor document is read as no anchor, not as no position`() {
        val item = SyncDeltaItemDto(
            kind = "state",
            bookId = "book-1",
            rev = 5,
            percent = 44.0,
            positionJson = "{ this is not json",
            positionFileId = "file-c",
        )
        val position = requireNotNull(ProgressMerge.remotePosition(item, json))
        assertNull(position.chapterIndex)
        assertEquals(44.0, requireNotNull(position.percent), 0.0)
        // The file id survives from the column, because the server wrote that one itself.
        assertEquals("file-c", position.fileId)
    }

    @Test
    fun `the anchor document is read when the server has one`() {
        val item = SyncDeltaItemDto(
            kind = "state",
            bookId = "book-1",
            rev = 6,
            percent = 8.0,
            positionJson = """{"chapterIndex":4,"charOffset":88,"fileId":"file-a","sha256":"sha-a"}""",
        )
        val position = requireNotNull(ProgressMerge.remotePosition(item, json))
        assertEquals(4, position.chapterIndex)
        assertEquals(88, position.charOffset)
        assertEquals("sha-a", position.sha256)
    }
}
