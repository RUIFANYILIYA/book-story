package dev.ebookd.core.data

import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.db.ShelfEditEntity
import dev.ebookd.core.net.BatchDeleteRequestDto
import dev.ebookd.core.net.BatchResultDto
import dev.ebookd.core.net.BatchStatusRequestDto
import dev.ebookd.core.net.BatchTagRequestDto
import dev.ebookd.core.net.EbookdApi
import dev.ebookd.core.net.TagDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

/** Why a batch did not happen. Three answers, because those are the three the reader can act on. */
enum class BatchFailure {
    NONE,

    /** No route to the server. Nothing was changed anywhere, which is the point of saying so. */
    OFFLINE,

    /** The session lacks the capability — a reader's account cannot tag shared metadata. */
    FORBIDDEN,
    OTHER,
}

/**
 * What a batch did: which ids took the change and which did not.
 *
 * [applied] is the list the caller is allowed to reflect locally. It is deliberately not "the
 * selection", and [failure] is deliberately not folded into the counts — a request that never
 * arrived must not be reported as "0 of 30 worked", because the reader needs to know the difference
 * between a server that refused and a tunnel that swallowed the train. [errors] carries the server's
 * per-id reason, which is the only way to tell a stale selection from one worth retrying.
 */
data class BatchReport(
    val applied: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val failure: BatchFailure = BatchFailure.NONE,
    val errors: Map<String, String> = emptyMap(),
)

/**
 * Which of the queued statuses are still owed after an attempt.
 *
 * The distinction that matters is between a server that said no and a server that was not there.
 * Keeping a refusal in the queue builds a backlog that every later sync replays and re-refuses;
 * dropping it loses the reader's choice silently. So: a refusal is final, a book that no longer
 * exists is final, and anything that never got an answer stays.
 */
object ShelfEditQueue {

    /** Ids from [ids] that the queue should still hold, given what the batch reported. */
    fun stillOwed(
        ids: List<String>,
        report: BatchReport,
    ): List<String> = when (report.failure) {
        BatchFailure.FORBIDDEN -> emptyList()
        BatchFailure.NONE -> report.failed.filterNot { report.errors[it] == BOOK_GONE }
        BatchFailure.OFFLINE, BatchFailure.OTHER -> report.failed
    }.distinct().filter { it in ids }

    private const val BOOK_GONE = "book_not_found"
}

/**
 * Filing several books at once, through the server's batch endpoints.
 *
 * The rule this class holds to is that **the cache is written after the server agrees**, never
 * before. This matters more here than it would elsewhere, because the shelf's refresh does an
 * unconditional `clear()` and re-fetch: a speculative write to `books` would be overwritten by
 * whichever round trip finished first, and the badge would flicker back mid-flight.
 *
 * The exception is [markStatus], and it is the same rule seen from the other side: what gets written
 * first is not the cache but the *queue* — a durable record of what the reader asked for, which the
 * shelf draws until the server confirms it. `books.status` is still only ever written with ids the
 * server accepted.
 */
@Singleton
class BatchRepository @Inject constructor(
    private val db: EbookdDatabase,
    private val api: EbookdApi,
) {

    private val books get() = db.books()
    private val edits get() = db.shelfEdits()

    /**
     * The reader's own state, so this one is available to any signed-in user — and the one batch
     * action worth remembering when there is no server to tell.
     *
     * The intent is written down before the request goes out, not after it succeeds. That is what
     * makes an offline 已读 survive a restart: `books.status` is the server's answer and every
     * refresh overwrites it, so a badge chosen on a train would otherwise vanish the first time the
     * shelf re-paged.
     */
    suspend fun markStatus(
        ids: List<String>,
        status: String,
    ): BatchReport {
        val now = System.currentTimeMillis()
        edits.putAll(ids.map { ShelfEditEntity(bookId = it, status = status, enqueuedAt = now) })
        return sendStatus(ids, status)
    }

    /**
     * Everything the queue still owes, sent in one pass, grouped by the status it is owed under.
     *
     * The grouping is why a reader who marked five books 已读 and three 在读 on a train costs two
     * requests rather than eight: the endpoint takes one status per call and a list of books.
     */
    suspend fun drainPending(): BatchReport {
        var applied = listOf<String>()
        var failed = listOf<String>()
        var errors = emptyMap<String, String>()
        var failure = BatchFailure.NONE
        edits.pending().groupBy { it.status }.forEach { (status, rows) ->
            val report = sendStatus(rows.map { it.bookId }, status)
            applied += report.applied
            failed += report.failed
            errors += report.errors
            if (report.failure != BatchFailure.NONE) failure = report.failure
        }
        return BatchReport(applied, failed, failure, errors)
    }

    /** The unacknowledged choices, as the shelf draws them: book id → the status the reader picked. */
    fun pendingStatuses(): Flow<Map<String, String>> =
        edits.observeAll().map { rows -> rows.associate { it.bookId to it.status } }

    fun observePendingCount(): Flow<Int> = edits.observeCount()

    private suspend fun sendStatus(
        ids: List<String>,
        status: String,
    ): BatchReport {
        val report = send(ids) { api.batchStatus(BatchStatusRequestDto(it, status = status)) }
        if (report.applied.isNotEmpty()) {
            books.setStatus(report.applied, status)
            edits.drop(report.applied)
        }
        val owed = ShelfEditQueue.stillOwed(ids, report).toSet()
        val settled = ids.filterNot { it in owed } - report.applied.toSet()
        if (settled.isNotEmpty()) edits.drop(settled)
        if (owed.isNotEmpty()) edits.recordFailures(owed.toList(), report.failure.name)
        return report
    }

    /**
     * Tagging shared metadata, which needs `library:edit`.
     *
     * No local write, because the cached shelf row carries no tags: there is nothing here to update.
     * The book's own page fetches its tags live and shows the change the moment it is opened.
     */
    suspend fun addTags(
        ids: List<String>,
        names: List<String>,
    ): BatchReport = send(ids) { api.batchTag(BatchTagRequestDto(it, add = names)) }

    /**
     * Soft delete: the catalogue drops the book, its files stay for the retention window.
     *
     * The local row goes only for the ids the server accepted — a refusal leaves the shelf exactly
     * as it was, which is the only honest outcome when the thing being removed is a book.
     */
    suspend fun removeBooks(ids: List<String>): BatchReport {
        val report = send(ids) { api.batchDelete(BatchDeleteRequestDto(it)) }
        if (report.applied.isNotEmpty()) books.drop(report.applied)
        return report
    }

    /**
     * The tags the library already has, for the picker.
     *
     * A failure is handed to the screen rather than turned into an empty list: the picker can still
     * take a typed name (the server creates the tag on the way in), and "no existing tags to show"
     * and "could not reach the server" are different things to say.
     */
    suspend fun tags(query: String?): Result<List<TagDto>> =
        runCatching { api.tags(query?.takeIf { it.isNotBlank() }).items }

    /**
     * One request per chunk of the selection, accumulating per-id verdicts.
     *
     * Chunked because the server rejects a body over [MAX_BATCH_REQUEST] ids rather than clamping
     * it, and a selection grown by repeated 全选 can pass that. Stopping at the first transport
     * failure rather than retrying per chunk is deliberate: the reason it failed — offline, or no
     * permission — is the same reason the next chunk would fail, and a partial batch is reported as
     * exactly that.
     */
    private suspend fun send(
        ids: List<String>,
        call: suspend (List<String>) -> BatchResultDto,
    ): BatchReport {
        var applied = listOf<String>()
        var failed = listOf<String>()
        var errors = emptyMap<String, String>()
        var failure = BatchFailure.NONE
        for (chunk in ids.chunked(MAX_BATCH_REQUEST)) {
            val result = try {
                call(chunk)
            } catch (error: HttpException) {
                failure = if (error.code() == 403) BatchFailure.FORBIDDEN else BatchFailure.OTHER
                failed += chunk
                break
            } catch (error: IOException) {
                failure = BatchFailure.OFFLINE
                failed += chunk
                break
            }
            val split = result.split(chunk)
            applied += split.applied
            failed += split.failed
            errors += result.changed.mapNotNull { change -> change.error?.let { change.id to it } }
        }
        return BatchReport(applied, failed, failure, errors)
    }

    private companion object {

        /** `MAX_BATCH_SIZE` on the server (`http/validate.ts`); over it is a 400, not a clamp. */
        const val MAX_BATCH_REQUEST = 2000
    }
}
