package dev.ebookd.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A position that has been read but not yet told to the server.
 *
 * One row per book, not one per turn: a position supersedes the previous one for the same book, so
 * a reader who turns forty pages on a train owes the server one write, not forty. The row holds no
 * position of its own — the truth is [ReadingPositionEntity], read at drain time — because a queue
 * that carries its own copy of the payload can disagree with the table it came from, and the
 * disagreement only shows up as a reader resuming on a page they left an hour ago.
 */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey val bookId: String,
    val enqueuedAt: Long,
    /** Why the last attempt was refused, when one was — shown on the account screen. */
    val lastError: String? = null,
)

/**
 * How far the delta pull has read, and when it last worked.
 *
 * The cursor is the server's own revision counter, not a timestamp: the server assigns it inside
 * the transaction that writes the row, so it is a total order that two devices cannot disagree
 * about — which a pair of phone clocks can, by minutes.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val cursor: Long = 0,
    /** Wall clock of the last successful sync, for the account screen to show. */
    val lastSyncAt: Long? = null,
    /** The last failure, so a reader can be told why their progress is not on the server. */
    val lastError: String? = null,
) {
    companion object {
        const val SINGLETON = 1
    }
}

@Dao
interface SyncOutboxDao {

    @Query("SELECT * FROM sync_outbox ORDER BY enqueuedAt ASC")
    suspend fun pending(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun put(row: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE bookId = :bookId")
    suspend fun clear(bookId: String)

    @Query("UPDATE sync_outbox SET lastError = :error WHERE bookId = :bookId")
    suspend fun recordFailure(bookId: String, error: String?)
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = ${SyncStateEntity.SINGLETON}")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = ${SyncStateEntity.SINGLETON}")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun put(state: SyncStateEntity)
}

/**
 * A reading status the reader chose here that the server does not have yet.
 *
 * The position outbox needs no value of its own because `reading_position` already holds one, and
 * the newest write is the one to send. A status has no such table: `books.status` is the server's
 * answer, overwritten by every refresh, so an offline 已读 would vanish on the next pull-to-refresh
 * — the reader would see the badge they chose disappear and reasonably conclude the app is broken.
 * This row is the durable form of that intent: one per book, superseded by the next choice for the
 * same book, deleted when the server confirms.
 *
 * It is also what the shelf draws while the change is unacknowledged, which is why it survives a
 * process death: the badge a reader set on a train is still there when they open the app again.
 */
@Entity(tableName = "shelf_edit")
data class ShelfEditEntity(
    @PrimaryKey val bookId: String,
    /** The server's enum: none, reading, finished, abandoned, on_hold. */
    val status: String,
    val enqueuedAt: Long,
    /** Why the last attempt was refused, when one was — shown on the account screen. */
    val lastError: String? = null,
)

@Dao
interface ShelfEditDao {

    @Query("SELECT * FROM shelf_edit ORDER BY enqueuedAt ASC")
    suspend fun pending(): List<ShelfEditEntity>

    @Query("SELECT * FROM shelf_edit")
    fun observeAll(): Flow<List<ShelfEditEntity>>

    @Query("SELECT COUNT(*) FROM shelf_edit")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun putAll(rows: List<ShelfEditEntity>)

    @Query("DELETE FROM shelf_edit WHERE bookId IN (:bookIds)")
    suspend fun drop(bookIds: List<String>)

    @Query("UPDATE shelf_edit SET lastError = :error WHERE bookId IN (:bookIds)")
    suspend fun recordFailures(
        bookIds: List<String>,
        error: String?,
    )
}
