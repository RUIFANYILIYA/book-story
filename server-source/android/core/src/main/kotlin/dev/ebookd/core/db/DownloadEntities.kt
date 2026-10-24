package dev.ebookd.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One row per book the reader asked to have offline.
 *
 * It stores **intent**, not progress: no `downloadedBytes` column, because a number in a table and
 * the length of the temp file can disagree after a crash and then the client has to decide which one
 * to believe. The file's length is the cursor; this row's job is to survive a restart with the
 * answer to "what was I supposed to fetch".
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    /** The immutable file id — also the URL, also the cache key. */
    @PrimaryKey val fileId: String,
    val bookId: String,
    val title: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String?,
    val state: String,
    val error: String? = null,
    val updatedAt: Long,
) {
    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"

        val ACTIVE = listOf(QUEUED, RUNNING)
    }
}

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE state IN (:states)")
    suspend fun findActive(states: List<String> = DownloadTaskEntity.ACTIVE): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE fileId = :fileId")
    suspend fun find(fileId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_task WHERE fileId = :fileId")
    fun observe(fileId: String): Flow<DownloadTaskEntity?>

    /**
     * The most recent thing the reader asked to keep for this book. Whether the bytes actually
     * arrived is answered by the object store, not by `state` — the store is the truth about what is
     * on the device, and a row left at `running` by a crash still describes a complete object.
     */
    @Query(
        "SELECT * FROM download_task WHERE bookId = :bookId AND sha256 IS NOT NULL " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun findLatestWithSha(bookId: String): DownloadTaskEntity?

    @Upsert
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("UPDATE download_task SET state = :state, error = NULL, updatedAt = :now WHERE fileId = :fileId")
    suspend fun setState(fileId: String, state: String, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE download_task SET state = :state, error = :error, updatedAt = :now WHERE fileId = :fileId",
    )
    suspend fun fail(fileId: String, error: String?, state: String = DownloadTaskEntity.FAILED, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_task WHERE fileId = :fileId")
    suspend fun remove(fileId: String)

    /**
     * How many *other* tasks point at the same content. The store is content-addressed, so two
     * formats of one book share an object and deleting it for one of them would silently break the
     * other. Counted in SQL rather than by loading tasks and filtering in Kotlin.
     */
    @Query("SELECT COUNT(*) FROM download_task WHERE sha256 = :sha AND fileId != :exceptFileId")
    suspend fun countOthersWithSha(sha: String, exceptFileId: String): Int
}
