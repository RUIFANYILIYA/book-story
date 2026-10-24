package dev.ebookd.core.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The local catalogue: one row per book the server has shown us.
 *
 * `position` is what makes local paging honest. The server's order is keyset and opaque, so the
 * local table cannot reproduce it by sorting a column; instead the mediator records the order the
 * rows arrived in. Without it, a page appended later can sort *above* the rows already on screen
 * and the shelf visibly shuffles while the reader is looking at it.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val position: Long,
    val title: String,
    /** Display string; the wire type is a list, and the shelf never needs the parts separately. */
    val authors: String,
    val seriesName: String?,
    val seriesIndex: Double?,
    val year: Int?,
    val formats: String,
    val hasCover: Boolean,
    val coverUrl: String?,
    val sizeBytes: Long,
    val fileCount: Int,
    val needsReview: Boolean,
    val status: String?,
    val percent: Double?,
    val lastReadAt: String?,
    val contentUpdatedAt: String?,
    val updatedAt: String?,
)

/**
 * The signed cursor for one list, and whether the list ended.
 *
 * Keyed by the *query* rather than kept as a single row, because the server rejects a cursor that
 * arrives with a different sort or filter than it was issued for — storing one cursor for "the
 * library" would break the moment a second ordering existed.
 */
@Entity(tableName = "library_remote_keys")
data class RemoteKeyEntity(
    @PrimaryKey val query: String,
    val nextCursor: String?,
    val endReached: Boolean,
)

/**
 * The reader's own position for one book, as the shelf reads it.
 *
 * A separate query rather than a join in [BookDao.pagingSource], and that is a decision about
 * invalidation rather than about style: Room invalidates a query when any table it reads changes,
 * so a shelf that joined `reading_position` would be re-paged every time the reader turned a page.
 * This one is a few hundred short rows, read once and combined in memory.
 */
data class LocalPosition(
    val bookId: String,
    /** 0..100 of the book, the unit the server uses and the reader's own row carries. */
    val percent: Double,
)

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY position ASC")
    fun pagingSource(): PagingSource<Int, BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun find(id: String): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(books: List<BookEntity>)

    @Query("DELETE FROM books")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    /**
     * Files the server has just confirmed a change for.
     *
     * Only ever called *after* the batch endpoint accepted the write, so this is the cache catching
     * up with the server rather than the app deciding for itself: the optimistic value lives in the
     * screen's own overlay and never touches this table, which is what keeps a failed request from
     * needing a revert here.
     */
    @Query("UPDATE books SET status = :status WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<String>, status: String?)

    /** Drops rows the server no longer lists. The next refresh would drop them anyway; this saves the reader a stale cover. */
    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun drop(ids: List<String>)
}

@Dao
interface RemoteKeyDao {

    @Query("SELECT * FROM library_remote_keys WHERE query = :query")
    suspend fun find(query: String): RemoteKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: RemoteKeyEntity)

    @Query("DELETE FROM library_remote_keys WHERE query = :query")
    suspend fun clear(query: String)
}
