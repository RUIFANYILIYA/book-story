package dev.ebookd.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.db.RemoteKeyEntity
import dev.ebookd.core.net.EbookdApi
import java.io.IOException

/**
 * Fills the local catalogue from the server, one keyset page at a time.
 *
 * Two things here are less obvious than they look. A REFRESH clears the table *and* the cursor
 * together, inside one transaction, because a crash between the two would leave rows from one
 * ordering labelled as a page of another. And the rows are inserted at continuing positions rather
 * than sorted by a column: the server's order is opaque, so the only faithful record of it is the
 * order the rows arrived in.
 */
@OptIn(ExperimentalPagingApi::class)
class LibraryRemoteMediator(
    private val db: EbookdDatabase,
    private val api: EbookdApi,
    private val query: LibraryQuery,
) : RemoteMediator<Int, BookEntity>() {

    override suspend fun load(loadType: LoadType, state: PagingState<Int, BookEntity>): MediatorResult {
        val cursor = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> {
                val keys = db.remoteKeys().find(query.key)
                if (keys == null || keys.endReached) return MediatorResult.Success(endOfPaginationReached = true)
                keys.nextCursor ?: return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        return try {
            val page = api.books(
                cursor = cursor,
                limit = EbookdApi.DEFAULT_PAGE_SIZE,
                query = query.search,
                format = query.format,
                sort = query.sort,
                order = query.order,
            )
            val endReached = !page.hasMore || page.nextCursor == null
            db.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    db.books().clear()
                    db.remoteKeys().clear(query.key)
                }
                val base = db.books().count().toLong()
                db.books().upsert(page.items.mapIndexed { index, book -> book.toEntity(base + index) })
                db.remoteKeys().upsert(RemoteKeyEntity(query.key, page.nextCursor, endReached))
            }
            MediatorResult.Success(endOfPaginationReached = endReached)
        } catch (_: IOException) {
            // Reported as an error, not as "the end": the shelf must keep showing what it has and
            // offer a retry, rather than claim the library is empty because the wifi is off.
            MediatorResult.Error(IOException("书架刷新失败：无法连接服务器"))
        } catch (failure: Exception) {
            MediatorResult.Error(failure)
        }
    }
}
