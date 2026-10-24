package dev.ebookd.core.data

import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.db.ReaderOpenRow
import dev.ebookd.core.db.ReadingPositionEntity
import dev.ebookd.core.storage.ObjectStore
import dev.ebookd.core.text.PercentAnchor
import dev.ebookd.core.text.StageTimer
import dev.ebookd.core.text.TxtBookOpener
import dev.ebookd.core.text.TxtBookOpener.OpenedTxtBook
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a reader can *do* about a book that will not open. */
enum class UnreadableReason {
    /** The bytes are not on this device — which is fixable by fetching them, so callers branch on it. */
    NOT_ON_DEVICE,

    /** Here, but this build cannot render it. */
    FORMAT_UNSUPPORTED,
}

/** A book that cannot be opened, with the reason a reader can act on. */
class UnreadableBook(val reason: UnreadableReason, message: String) : Exception(message)

/**
 * The reader's only path to a book: bytes on disk in, a document out, plus where they stopped.
 *
 * Two rules live here. The object store, not the download row, decides whether a book can be read —
 * a task left at `running` by a crash still describes a complete file, and a row claiming `done` for
 * bytes the user deleted would open an empty book. And the position saved is a chapter plus a
 * character offset into that chapter's *laid-out* text, which is the only coordinate that means the
 * same thing before and after a font-size change.
 *
 * Opening is one query, not two: the book and the position arrive in a single row, so a reopen
 * makes exactly one round trip to Room and then seeks into the normalized text.
 */
@Singleton
class ReaderRepository @Inject constructor(
    private val db: EbookdDatabase,
    private val objects: ObjectStore,
    private val opener: TxtBookOpener,
    private val sync: SyncRepository,
) {

    data class Readable(
        val bookId: String,
        val title: String,
        val book: OpenedTxtBook,
        val charset: String,
        val fromCache: Boolean,
        val tookMillis: Long,
        /** Where the reader stopped, or null for a book that has never been read. */
        val resume: ReadingPositionEntity?,
        /** True when the percentage had to answer, because the anchor was measured elsewhere. */
        val resumeFromPercent: Boolean,
        /** The downloaded file being read, which a progress push names as where the position is. */
        val fileId: String,
        /** Its hash, and now also the thing a stored anchor is checked against before being used. */
        val sha256: String,
        /** Laps taken on the way here; the caller keeps adding to it until the first page lands. */
        val stages: StageTimer,
    )

    /**
     * Opens a book, off the main thread from the first statement.
     *
     * The whole body is on IO, including the query, and that is not tidiness: a suspend Room call
     * resumes on whichever thread asked for it, and asked from the main thread it waits for the main
     * thread to finish composing the reader screen before the file read even begins. Measured on the
     * emulator, a bare main-to-IO round trip cost 101 ms during an open while the query behind it
     * cost 39 ms and the second one 6 — the wait was the dispatcher, not SQLite. Started from IO, the
     * query and the parse overlap that composition and the reader waits for the slower of the two.
     */
    suspend fun open(bookId: String): Result<Readable> = withContext(Dispatchers.IO) {
        runCatching {
            val stages = StageTimer()
            val row = db.readerOpens().openRow(bookId)
                ?: throw UnreadableBook(UnreadableReason.NOT_ON_DEVICE, "这本书还没有下载到本机。")
            stages.lap("db")
            val sha = row.sha256
            if (sha == null || !objects.contains(sha)) {
                // Also the in-flight case: a queued task already carries its file's hash, so the
                // object store is the only thing that can tell "half a book" from "a book".
                throw UnreadableBook(UnreadableReason.NOT_ON_DEVICE, "本机的副本还没到位。")
            }
            if (!row.format.equals("txt", ignoreCase = true)) {
                throw UnreadableBook(
                    UnreadableReason.FORMAT_UNSUPPORTED,
                    "阅读器目前只支持 TXT，${row.format.uppercase()} 还没接上。",
                )
            }
            stages.lap("object")
            val opened = opener.open(row.title, sha, stages)
            val resume = resolveResume(bookId, row, sha, opened)
            Readable(
                bookId = bookId,
                title = opened.title,
                book = opened,
                charset = opened.charset,
                fromCache = opened.fromCache,
                tookMillis = opened.tookMillis,
                resume = resume.first,
                resumeFromPercent = resume.second,
                fileId = row.fileId,
                sha256 = sha,
                stages = stages,
            )
        }
    }

    /**
     * Where to put the reader, and how much of the stored position to believe.
     *
     * The anchor is exact whenever it was measured against the file being opened — same hash, same
     * offsets, same paragraph. Against a *different* upload of the same book it is not usable at
     * all: the chapter number and the offset point into text that is no longer there, and following
     * them lands the reader on a plausible-looking wrong page, which is worse than landing them at
     * the beginning. The plan's rule for that case is to trust the percentage and nothing else, so
     * the percentage is resolved against the document's own chapter map — no layout, no font, the
     * same paragraph on any device.
     *
     * A position with no percentage at all and no matching hash has nothing usable in it, and the
     * book opens at the start rather than somewhere arbitrary.
     */
    private fun resolveResume(
        bookId: String,
        row: ReaderOpenRow,
        sha: String,
        opened: OpenedTxtBook,
    ): Pair<ReadingPositionEntity?, Boolean> {
        // The join hands back both fields or neither, so a chapter without an offset is a row that
        // is not there.
        val chapter = row.chapterIndex ?: return null to false
        val stored = ReadingPositionEntity(
            bookId = bookId,
            chapterIndex = chapter,
            charOffset = row.charOffset ?: 0,
            updatedAt = row.positionUpdatedAt ?: 0L,
            percent = row.percent ?: 0.0,
            fileId = row.fileId,
            sha256 = row.positionSha256,
            baseRev = row.baseRev ?: 0L,
        )
        if (row.positionSha256 != null && row.positionSha256 == sha) return stored to false
        val percent = row.percent ?: return null to false
        val starts = opened.outline().sortedBy { it.index }.map { it.charStart }.toIntArray()
        val derived = PercentAnchor.derive(starts, opened.normalizedChars, percent)
            ?: return null to false
        return stored.copy(chapterIndex = derived.first, charOffset = derived.second) to true
    }

    /**
     * Records where the reader is, and queues the news for the server.
     *
     * The percentage is computed by the caller, which is the only place that knows the chapter's
     * offset in the document; everything else about the row is this device's answer to "which file
     * was this position measured in".
     */
    suspend fun savePosition(
        bookId: String,
        chapterIndex: Int,
        charOffset: Int,
        percent: Double,
        fileId: String?,
        sha256: String?,
    ) {
        val previous = db.readingPositions().find(bookId)
        db.readingPositions().save(
            ReadingPositionEntity(
                bookId = bookId,
                chapterIndex = chapterIndex,
                charOffset = charOffset,
                updatedAt = System.currentTimeMillis(),
                percent = percent,
                fileId = fileId,
                sha256 = sha256,
                // Reading does not create a revision; it only moves the position the next push
                // will offer. Keeping the old one is what lets the server say "someone wrote
                // since", rather than accepting every write as if it were based on the latest.
                baseRev = previous?.baseRev ?: 0L,
            ),
        )
        sync.enqueue(bookId)
    }
}
