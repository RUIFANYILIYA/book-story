package dev.ebookd.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.ebookd.core.db.DownloadTaskEntity
import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.download.RangeDownloader
import dev.ebookd.core.net.EbookdApi
import okhttp3.Request

/**
 * Runs one download to completion, in the background, across a process death.
 *
 * The retry rule is the whole reason this is a worker rather than a coroutine: a transfer that
 * fails because the train went through a tunnel should resume from the temp file when the network
 * comes back, and `Result.retry()` is what does that — WorkManager reschedules it with backoff, and
 * the transfer's own `resumedFrom` proves the cursor came off disk rather than from a counter. It is
 * not gated on a connectivity constraint; see [OfflineRepository.enqueue] for why.
 *
 * An integrity failure is *not* retried. Re-fetching the same bytes from the same server and
 * hashing them again reaches the same conclusion; retrying would just burn the reader's battery
 * while the real cause (a bad file on the server, or a proxy rewriting bytes) stays unfixed.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: EbookdDatabase,
    private val downloader: RangeDownloader,
    private val sync: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fileId = inputData.getString(KEY_FILE_ID) ?: return Result.failure()
        val tasks = db.downloadTasks()
        val task = tasks.find(fileId) ?: return Result.success()
        if (task.state == DownloadTaskEntity.CANCELLED) return Result.success()

        tasks.setState(fileId, DownloadTaskEntity.RUNNING)

        val outcome = downloader.download(
            fileId = fileId,
            expectedSha256 = task.sha256,
            urlSupplier = { start, ifRange ->
                Request.Builder().url(EbookdApi.fileDownloadUrl(fileId)).apply {
                    if (start != null) {
                        header("Range", "bytes=$start-")
                        ifRange?.let { header("If-Range", it) }
                    }
                }.build()
            },
        )

        return when (outcome.status) {
            RangeDownloader.Status.COMPLETED, RangeDownloader.Status.ALREADY_PRESENT -> {
                tasks.setState(fileId, DownloadTaskEntity.DONE)
                // The bytes just came off the network, so the server is reachable *now* — which
                // makes this the best moment to drain positions read while it was not (the reader
                // who tidied their shelf offline and is stocking up before the next train). Paying
                // for ALREADY_PRESENT too costs one attempt that backs off on its own.
                sync.requestSync()
                Result.success()
            }

            RangeDownloader.Status.INTEGRITY_FAILURE -> {
                tasks.fail(fileId, outcome.message)
                Result.failure()
            }

            RangeDownloader.Status.FAILED -> {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    tasks.fail(fileId, outcome.message)
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val KEY_FILE_ID = "fileId"
        private const val MAX_ATTEMPTS = 6
    }
}
