package dev.ebookd.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.net.BookDetailDto
import dev.ebookd.core.net.BookSummaryDto
import dev.ebookd.core.net.EbookdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The library as the screens use it: the shelf is offline-first (cached rows render immediately,
 * the mediator refreshes behind them), while search and book detail are online for now.
 *
 * That split is deliberate rather than unfinished: the shelf is what a reader opens on a train, and
 * search results for a server that is unreachable are not a thing a client can produce honestly
 * until local full-text search exists — which the plan puts in the offline milestone.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val db: EbookdDatabase,
    private val api: EbookdApi,
) {

    @OptIn(ExperimentalPagingApi::class)
    fun shelf(): Flow<PagingData<BookEntity>> = Pager(
        config = PagingConfig(
            pageSize = EbookdApi.DEFAULT_PAGE_SIZE,
            // Placeholders would draw empty covers over books that exist; the shelf is short
            // enough that an honest "still loading" reads better.
            enablePlaceholders = false,
        ),
        remoteMediator = LibraryRemoteMediator(db, api, LibraryQuery.SHELF),
        pagingSourceFactory = { db.books().pagingSource() },
    ).flow

    /**
     * The reader's own percentage for every book this device has opened.
     *
     * Kept out of the shelf's query on purpose. Room invalidates a query whose tables changed, so a
     * page through `books JOIN reading_position` would be re-run on every page turn — during the
     * one activity that should not be doing the shelf's work. This is a few hundred rows of two
     * columns, re-read by Room's own invalidation when a position changes, and the two are put
     * together in memory by the screen that draws them.
     */
    fun localPercents(): Flow<Map<String, Double>> =
        db.readingPositions().observePercents().map { rows -> rows.associate { it.bookId to it.percent } }

    /**
     * One book's own percentage, or null when this device has never been in it.
     *
     * The single-book form of [localPercents], for the detail screen: progress is written by the
     * reader's own device rather than by the library, so the number the badge shows has to come from
     * the same row the reader wrote — otherwise the shelf card and the book's own page disagree
     * about how far in the reader is, and the one that loses is the newer.
     */
    fun localPercent(bookId: String): Flow<Double?> = db.readingPositions().observePercent(bookId)

    fun observeCached(id: String): Flow<BookEntity?> = db.books().observe(id)

    /** Online-only, and the caller shows the reason when it fails. */
    suspend fun search(text: String): Result<List<BookSummaryDto>> =
        runCatching { api.books(query = text, limit = EbookdApi.DEFAULT_PAGE_SIZE, sort = "title", order = "asc").items }

    suspend fun detail(id: String): Result<BookDetailDto> = runCatching { api.book(id) }

    /** Total rows in the cache, for a screen that wants to say "showing what was downloaded". */
    suspend fun cachedCount(): Int = db.books().count()

    /** Local-only lookup, used to show something when the server cannot be reached. */
    suspend fun cached(id: String): BookEntity? = db.books().find(id)
}
