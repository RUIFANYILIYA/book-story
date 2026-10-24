package dev.ebookd.core.data

import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.net.BookSummaryDto

/**
 * Identifies one list: the shelf, or a filtered view of it.
 *
 * `key` is used twice and has to mean the same thing in both places — as the Room row holding this
 * list's signed cursor, and as the identity Paging uses to decide when to reset instead of append.
 */
data class LibraryQuery(
    val key: String,
    val sort: String,
    val order: String,
    val format: String? = null,
    val search: String? = null,
) {
    companion object {
        /** The shelf: most recently read first, which is the ordering a reader actually wants. */
        val SHELF = LibraryQuery(key = "shelf", sort = "lastRead", order = "desc")
    }
}

internal fun BookSummaryDto.toEntity(position: Long) = BookEntity(
    id = id,
    position = position,
    title = title,
    authors = authors.joinToString("、"),
    seriesName = seriesName,
    seriesIndex = seriesIndex,
    year = year,
    formats = formats.joinToString(","),
    hasCover = hasCover,
    coverUrl = coverUrl,
    sizeBytes = sizeBytes,
    fileCount = fileCount,
    needsReview = needsReview,
    status = my.status,
    percent = my.percent,
    lastReadAt = my.lastReadAt,
    contentUpdatedAt = contentUpdatedAt,
    updatedAt = updatedAt,
)
