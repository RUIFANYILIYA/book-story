package dev.ebookd.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 5 adds `shelf_edit`: the reading statuses this device chose that the server has not
 * accepted yet. Version 4 added the sync tables and the fields a position needs to travel. The
 * remaining tables the plan lists — chapters, `txt_doc_meta`, `page_cache`, bookmarks — arrive with
 * their own milestones.
 *
 * This is the first version bump that ships with a real migration rather than relying on the
 * destructive fallback: `download_task` and `reading_position` hold things the reader made — an
 * offline library and a place in each book — and a schema change may not be what costs them.
 */
@Database(
    entities = [
        BookEntity::class,
        RemoteKeyEntity::class,
        DownloadTaskEntity::class,
        ReadingPositionEntity::class,
        SyncOutboxEntity::class,
        SyncStateEntity::class,
        ShelfEditEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class EbookdDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun remoteKeys(): RemoteKeyDao
    abstract fun downloadTasks(): DownloadTaskDao
    abstract fun readingPositions(): ReadingPositionDao
    abstract fun readerOpens(): ReaderOpenDao
    abstract fun syncOutbox(): SyncOutboxDao
    abstract fun syncState(): SyncStateDao
    abstract fun shelfEdits(): ShelfEditDao

    companion object {
        const val NAME = "ebookd.db"
    }
}
