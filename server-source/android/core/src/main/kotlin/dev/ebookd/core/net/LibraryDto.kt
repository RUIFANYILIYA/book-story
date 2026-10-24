package dev.ebookd.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Library wire types. The list response carries the caller's own reading state (`my`) joined in, so
 * the shelf never issues a follow-up request per visible cover — which on a shelf of forty books
 * would be forty round trips before the first pixel is final.
 */

@Serializable
data class MyStateDto(
    val status: String? = null,
    val percent: Double? = null,
    val lastReadAt: String? = null,
)

@Serializable
data class BookSummaryDto(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    val year: Int? = null,
    val language: String? = null,
    /** Every format held for this book, so the shelf can badge what a download will cost. */
    val formats: List<String> = emptyList(),
    val hasCover: Boolean = false,
    /** Relative path, or null when no cover exists — never a URL that would render as a broken image. */
    val coverUrl: String? = null,
    val updatedAt: String? = null,
    /** Bumped when the bytes or the cover change; this is the app's cache key. */
    val contentUpdatedAt: String? = null,
    val sizeBytes: Long = 0,
    val fileCount: Int = 0,
    /** True when the title was inferred from the filename rather than read from the file. */
    val needsReview: Boolean = false,
    val my: MyStateDto = MyStateDto(),
)

@Serializable
data class BookPageDto(
    val items: List<BookSummaryDto> = emptyList(),
    /** Opaque and signed. Null on the last page. */
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val limit: Int = 0,
    val sort: String = "addedAt",
    val order: String = "desc",
)

@Serializable
data class BookFileDto(
    /** Immutable: this id is both the download path and the cache key for the bytes. */
    val id: String,
    val format: String = "",
    val filename: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    /** `size_bytes`: the file entries are the one place the API is not camelCase. */
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val sha256: String? = null,
    val charset: String? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    val drm: Boolean = false,
    val status: String? = null,
)

@Serializable
data class BookDetailDto(
    val book: BookDetailBookDto = BookDetailBookDto(),
    val files: List<BookFileDto> = emptyList(),
)

/**
 * The detail endpoint's shape, read off a running server rather than assumed.
 *
 * Two things are surprising and both are load-bearing. The nested `book` object is snake_case
 * (`has_cover`, `published_year`, `storage_bytes`) while the *list* items are camelCase — so a DTO
 * that looks like the list silently fills every field with its default, which is worse than a
 * crash because the screen still renders, just empty. And the detail carries no author list:
 * contributors are their own resource, so the UI takes authors from the cached row instead of
 * pretending this object has them.
 */
@Serializable
data class BookDetailBookDto(
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    @SerialName("published_year") val year: Int? = null,
    val language: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("has_cover") val hasCover: Boolean = false,
    @SerialName("needs_review") val needsReview: Boolean = false,
    @SerialName("storage_bytes") val sizeBytes: Long = 0,
    @SerialName("content_updated_at") val contentUpdatedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("series_index") val seriesIndex: Double? = null,
    /** The caller's own state, which the detail response returns as a sibling of `book`. */
    val my: MyStateDto = MyStateDto(),
)
