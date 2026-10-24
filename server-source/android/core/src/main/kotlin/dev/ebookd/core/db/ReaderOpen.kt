package dev.ebookd.core.db

import androidx.room.Dao
import androidx.room.Query

/**
 * The one row a reopen needs: the book to open, and where in it the reader stopped.
 *
 * Two queries used to answer this, and they cost like two: measured on the emulator, the pair spent
 * 87 ms of a warm reopen and 171 ms of a cold one, and each call reported the same number — the
 * second was queueing behind the first. One statement answers both questions, which also makes the
 * reopen path literally what the plan describes: one SELECT and a seek into the normalized text.
 *
 * Only the columns the reader uses are selected. `state` and `error` are the download's business,
 * and `updatedAt` on the task is an ordering key rather than a value anyone reads.
 *
 * The position travels with the file it was measured against ([positionSha256], and [percent] as the
 * version-independent fallback), because the two are only meaningful together: a saved offset whose
 * file hash no longer matches the file being opened points into text that is not there any more.
 */
data class ReaderOpenRow(
    val title: String,
    val sha256: String?,
    /** The id of the downloaded file — what a progress push names as the file the position is in. */
    val fileId: String,
    val format: String,
    /** Null when the reader has never been in this book: the LEFT JOIN invents a row of nulls. */
    val chapterIndex: Int?,
    val charOffset: Int?,
    val positionUpdatedAt: Long?,
    val percent: Double?,
    val positionSha256: String?,
    val baseRev: Long?,
)

@Dao
interface ReaderOpenDao {

    /**
     * The newest downloaded copy of [bookId], joined with the reader's place in that book.
     *
     * `sha256 IS NOT NULL` selects a task that knows *which bytes* to open — it does not claim they
     * are here. A request queues with the file's hash already filled in, so a queued or failed
     * download passes this filter and is caught downstream, where the object store answers whether
     * the file exists. The join is a LEFT one because the position is the part that may be missing —
     * a book downloaded and never read is still a book that opens.
     */
    @Query(
        "SELECT t.title AS title, t.sha256 AS sha256, t.fileId AS fileId, t.format AS format, " +
            "p.chapterIndex AS chapterIndex, p.charOffset AS charOffset, " +
            "p.updatedAt AS positionUpdatedAt, p.percent AS percent, " +
            "p.sha256 AS positionSha256, p.baseRev AS baseRev " +
            "FROM download_task AS t LEFT JOIN reading_position AS p ON p.bookId = t.bookId " +
            "WHERE t.bookId = :bookId AND t.sha256 IS NOT NULL " +
            "ORDER BY t.updatedAt DESC LIMIT 1",
    )
    suspend fun openRow(bookId: String): ReaderOpenRow?
}
