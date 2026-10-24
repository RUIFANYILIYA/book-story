package dev.ebookd.core.data

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.db.ReadingPositionEntity
import dev.ebookd.core.db.SyncOutboxEntity
import dev.ebookd.core.db.SyncStateEntity
import dev.ebookd.core.net.EbookdApi
import dev.ebookd.core.net.SyncChangeDto
import dev.ebookd.core.net.SyncPositionDto
import dev.ebookd.core.net.SyncPushPayloadDto
import dev.ebookd.core.net.SyncPushRequestDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Keeps the server's copy of "where the reader is" in step with this device's.
 *
 * The shape of the exchange is the server's, and it is worth restating because the order matters:
 * **pull first, then push**. Pulling first means a push normally carries the revision the server
 * actually has, so the conflict path — which is the expensive, ambiguous one — is rare rather than
 * normal. It also means the merge has already been applied when a change goes up, so what is sent
 * is the merged truth rather than this device's opinion of it.
 *
 * Pushing is a drain, not a stream. [enqueue] records "this book's position changed" and the
 * position table holds the value; a reader who turns forty pages on a train owes one write, not
 * forty. The worker is unique work with `KEEP`, so a save does not start a second sync, and the
 * drain loops until the outbox is empty — which is what makes a write that arrives *during* a sync
 * get sent rather than queued for the next app launch.
 *
 * WorkManager has no connectivity constraint here, for the reason [OfflineRepository.enqueue]
 * documents at length: on the API 36 emulator a `NetworkType.CONNECTED` constraint never dispatched
 * and left work stranded forever. A failed sync returns `retry()` instead and backs off by itself.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val db: EbookdDatabase,
    private val api: EbookdApi,
    private val json: Json,
    private val workManager: WorkManager,
    private val openBook: OpenBook,
    private val batch: BatchRepository,
) {

    private val outbox get() = db.syncOutbox()
    private val state get() = db.syncState()
    private val positions get() = db.readingPositions()

    /** One sync at a time: a manual "sync now" while the worker runs would otherwise double-pull. */
    private val mutex = Mutex()

    fun observeState(): Flow<SyncStateEntity?> = state.observe()

    fun observePending(): Flow<Int> = outbox.observeCount()

    /** Queues [bookId]'s current position and makes sure a sync is on its way. */
    suspend fun enqueue(bookId: String) {
        outbox.put(SyncOutboxEntity(bookId = bookId, enqueuedAt = System.currentTimeMillis()))
        requestSync()
    }

    /**
     * The background path: a position changed, so a sync should happen soon.
     *
     * `KEEP` and not `REPLACE`, which has a consequence worth naming: while a previous attempt is
     * waiting out its backoff, this call does **nothing** — the pending retry will pick the row up,
     * and an offline reader turning pages should not restart the timer forty times. The cost is that
     * a request made during a backoff is not honoured until the backoff expires, which is why
     * anything the reader is *watching for* — the app opening, a finger on "立即同步" — runs
     * [syncNow] inline instead of coming through here.
     */
    fun requestSync() {
        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }

    /**
     * One full exchange, in the order the class comment describes.
     *
     * The outcome is reported rather than swallowed so the worker can back off: a sync that could
     * not reach the server must not look like a sync that found nothing to do.
     */
    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        runCatching {
            pull()
            push()
            // Statuses the reader chose while there was nothing to send them to. By this point the
            // pull and the position push have both reached the server, so a queue that is still full
            // is a refusal rather than an outage — and it retries on the worker's schedule instead of
            // waiting for a page turn that may not come for a week.
            val owed = batch.drainPending()
            if (owed.failure == BatchFailure.OTHER) error("书架状态没能上传，稍后自动重试")
            val current = state.get() ?: SyncStateEntity()
            state.put(current.copy(lastSyncAt = System.currentTimeMillis(), lastError = null))
        }.onFailure { error ->
            runCatching {
                val current = state.get() ?: SyncStateEntity()
                state.put(current.copy(lastError = error.message ?: error.javaClass.simpleName))
            }
        }
    }

    /**
     * Everything the server has changed since the cursor, merged into the local positions.
     *
     * A pull that adopts a server position drops that book's outbox row: the server's value is now
     * the local one, and sending the value that just lost would undo the merge it just performed.
     * A pull that keeps the local position rebases it instead — see [ProgressMerge.rebase] for the
     * dead end that avoids.
     */
    private suspend fun pull() {
        var cursor = state.get()?.cursor ?: 0L
        var page = 0
        while (page < MAX_PAGES) {
            val delta = api.syncDelta(cursor)
            var held = false
            delta.items.forEach { item ->
                if (!item.isState) return@forEach
                val bookId = item.bookId ?: return@forEach
                // The book on the glass is the reader's; adopting a position under an open page
                // would move the page being read.
                if (bookId == openBook.bookId) {
                    held = true
                    return@forEach
                }
                val remote = ProgressMerge.remotePosition(item, json) ?: return@forEach
                val local = positions.find(bookId)
                val merged = ProgressMerge.merge(local, remote, bookId)
                if (merged != null) {
                    positions.save(merged)
                    outbox.clear(bookId)
                } else if (local != null) {
                    ProgressMerge.rebase(local, remote)?.let { positions.save(it) }
                }
            }
            // A held change is re-read by the next pull, so the cursor stops in front of it rather
            // than stepping over a change this device has not merged — otherwise the merge would be
            // lost, and with it the revision the pending push needs to be accepted.
            if (held) break
            if (delta.nextCursor > cursor) cursor = delta.nextCursor
            if (!delta.hasMore || delta.items.isEmpty()) break
            page++
        }
        state.put((state.get() ?: SyncStateEntity()).copy(cursor = cursor))
    }

    /**
     * Sends every pending position, and resolves what the server refuses.
     *
     * A refusal means somebody wrote after our last pull. The answer is to pull again — the merge
     * decides which position stands, and a position that loses is dropped from the outbox by the
     * pull itself — and then send whatever is still pending. Bounded, because "the other device
     * keeps writing" must end in a retry with backoff rather than in a loop that never returns.
     */
    private suspend fun push() {
        repeat(MAX_ROUNDS) {
            val pending = outbox.pending()
            if (pending.isEmpty()) return
            val sending = pending.mapNotNull { row ->
                val position = positions.find(row.bookId)
                if (position == null) outbox.clear(row.bookId)
                position?.let { row to it }
            }
            if (sending.isEmpty()) return

            val result = api.syncPush(SyncPushRequestDto(sending.map { (_, position) -> changeFor(position) }))

            result.applied.forEach { applied ->
                val (row, position) = sending.getOrNull(applied.index) ?: return@forEach
                // The revision the server assigned is what the next push offers as its base.
                positions.save(position.copy(baseRev = applied.rev))
                outbox.clear(row.bookId)
            }

            val conflicts = result.conflicts.filter { it.kind == SyncChangeDto.KIND_STATE }
            if (conflicts.isEmpty()) return@repeat
            conflicts.forEach { conflict ->
                sending.getOrNull(conflict.index)?.let { (row, _) ->
                    outbox.recordFailure(row.bookId, "server had newer rev ${conflict.serverRev}")
                }
            }
            pull()
        }
        // Still pending after the last round: report it, so the worker backs off instead of
        // claiming a sync that did not finish.
        if (outbox.pending().isNotEmpty()) {
            error("同步未完成：服务端上有更新的进度，稍后自动重试")
        }
    }

    private fun changeFor(position: ReadingPositionEntity) = SyncChangeDto(
        kind = SyncChangeDto.KIND_STATE,
        bookId = position.bookId,
        baseRev = position.baseRev,
        payload = SyncPushPayloadDto(
            percent = position.percent,
            position = SyncPositionDto(
                chapterIndex = position.chapterIndex,
                charOffset = position.charOffset,
                fileId = position.fileId,
                sha256 = position.sha256,
            ),
        ),
    )

    private companion object {
        const val MAX_PAGES = 10
        const val MAX_ROUNDS = 3
    }
}
