package dev.ebookd.core.data

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.ebookd.core.db.DownloadTaskDao
import dev.ebookd.core.db.DownloadTaskEntity
import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.net.BookFileDto
import dev.ebookd.core.storage.ObjectStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * "Keep this book on the device", as an operation the reader can see and undo.
 *
 * Three behaviours are deliberate:
 *
 *  - The work is unique **per file id** with `KEEP`, so tapping download twice does not start two
 *    transfers racing for the same temp file.
 *  - Cancelling removes the task *and* the partial temp file. Leaving bytes behind for something the
 *    reader asked to delete is how the library quietly outgrows the disk.
 *  - A book already stored under its own hash completes without a network round trip: the object
 *    tree, not the task row, is the truth about what is on the device.
 */
@Singleton
class OfflineRepository @Inject constructor(
    private val db: EbookdDatabase,
    private val objects: ObjectStore,
    private val workManager: WorkManager,
) {

    private val tasks: DownloadTaskDao get() = db.downloadTasks()

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = tasks.observeAll()

    fun observeTask(fileId: String): Flow<DownloadTaskEntity?> = tasks.observe(fileId)

    suspend fun requestOffline(bookId: String, title: String, file: BookFileDto) {
        tasks.upsert(
            DownloadTaskEntity(
                fileId = file.id,
                bookId = bookId,
                title = title,
                format = file.format,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                state = DownloadTaskEntity.QUEUED,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        enqueue(file.id)
    }

    suspend fun retry(fileId: String) {
        tasks.setState(fileId, DownloadTaskEntity.QUEUED)
        enqueue(fileId)
    }

    /** Stops the transfer and reclaims the partial bytes. The finished object is left alone. */
    suspend fun cancel(fileId: String) {
        workManager.cancelUniqueWork(workName(fileId))
        tasks.remove(fileId)
        objects.tempFor(fileId).let { temp -> if (temp.isFile) temp.delete() }
    }

    /**
     * Removes the local copy. Refuses to delete an object still referenced by another task, because
     * the store is content-addressed: two formats of one book, or the same book reached from two
     * shelves, share a file, and deleting it would break the other one silently.
     */
    suspend fun deleteOffline(fileId: String) {
        val task = tasks.find(fileId) ?: return
        workManager.cancelUniqueWork(workName(fileId))
        tasks.remove(fileId)
        val sha = task.sha256 ?: return
        if (tasks.countOthersWithSha(sha, exceptFileId = fileId) == 0) {
            objects.delete(sha)
        }
    }

    suspend fun isOffline(fileId: String): Boolean {
        val task = tasks.find(fileId) ?: return false
        val sha = task.sha256
        return task.state == DownloadTaskEntity.DONE || (sha != null && objects.contains(sha))
    }

    /**
     * No network constraint, deliberately.
     *
     * `NetworkType.CONNECTED` stranded every download on the API 36 emulator: WorkManager's
     * `NetworkRequestConstraintController` logged `onCapabilitiesChanged`, then
     * `Not dispatching constraint state yet: isBlocked=null`, then the blocked-status callback — and
     * never dispatched the now-satisfied constraint, so `GreedyScheduler` cancelled the job and the
     * task sat `queued` forever. Reproduced twice, including after WorkManager was initialized
     * correctly.
     *
     * Dropping it costs the little the constraint was worth for a self-hosted server on the reader's
     * own LAN, and buys back the retry path: [DownloadWorker] returns `retry()` on a transport
     * failure, which backs off and tries again on its own. A book that cannot be fetched is a
     * visible failed row with a 重试 button, not an invisible queue that never started.
     */
    private suspend fun enqueue(fileId: String) {
        workManager.enqueueUniqueWork(
            workName(fileId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_FILE_ID to fileId))
                .build(),
        )
    }

    private fun workName(fileId: String) = "download-$fileId"
}
