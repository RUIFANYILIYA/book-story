/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire format of an ebookd server (/api/v1).
 *
 * Two naming regimes coexist on purpose: list/detail summaries are camelCase while anything the
 * server emits as a raw database row is snake_case. `ignoreUnknownKeys` is enabled globally, which
 * means a wrong name here does not crash — it silently yields defaults, so the shelf renders empty.
 * Every snake_case field is therefore pinned with @SerialName instead of relying on the property
 * name matching.
 */

@Serializable
data class DeviceDto(
    val id: String? = null,
    val label: String? = null,
    val platform: String = "android",
    val clientVersion: String? = null
)

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    val device: DeviceDto? = null
)

@Serializable
data class RefreshRequestDto(
    val refreshToken: String
)

@Serializable
data class PasswordChangeRequestDto(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class PasswordChangeResultDto(
    /** Devices other than this one that the server signed out. */
    val otherSessionsRevoked: Int = 0
)

@Serializable
data class PublicUserDto(
    val id: String,
    val username: String,
    val displayName: String = username,
    val email: String? = null,
    val role: String = "user",
    val status: String = "active",
    val quotaBytes: Long? = null,
    val mustChangePassword: Boolean = false,
    val lastLoginAt: String? = null
)

@Serializable
data class SessionGrantDto(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 900,
    val refreshToken: String,
    val deviceId: String = "",
    val user: PublicUserDto
)

@Serializable
data class MeDto(
    val user: PublicUserDto,
    val capabilities: List<String> = emptyList(),
    val deviceId: String? = null,
    val storageQuotaBytes: Long? = null,
    val allowRegistration: Boolean = false
)

@Serializable
data class ErrorDto(
    val code: String,
    val message: String = ""
)

@Serializable
data class ErrorEnvelopeDto(
    val error: ErrorDto
)

@Serializable
data class MetaDto(
    val apiVersion: String = "",
    val serverVersion: String = "",
    val protocol: Int = 0,
    val upgradeRequired: Boolean = false
)

@Serializable
data class MyStateDto(
    val status: String? = null,
    val percent: Double? = null,
    val lastReadAt: String? = null
)

@Serializable
data class BookSummaryDto(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val hasCover: Boolean = false,
    val coverUrl: String? = null,
    val contentUpdatedAt: String = "",
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    /** The server shelf this book sits in, or null when unfiled. Optional: older servers omit it. */
    val categoryId: String? = null,
    val my: MyStateDto = MyStateDto()
)

@Serializable
data class BookPageDto(
    val items: List<BookSummaryDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val limit: Int = 50,
    val sort: String = "",
    val order: String = ""
)

/**
 * A server shelf. The endpoint emits raw rows, hence the snake_case names.
 *
 * `parent_id` is what makes this a tree; the server now refuses anything deeper than one level,
 * which is exactly what the library screen draws.
 */
@Serializable
data class CategoryDto(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    val name: String,
    val position: Int = 0,
    @SerialName("book_count") val bookCount: Int = 0
)

@Serializable
data class CategoryListDto(
    val items: List<CategoryDto> = emptyList()
)

@Serializable
data class BookFileDto(
    val id: String,
    val format: String = "",
    @SerialName("media_type") val mediaType: String = "",
    val filename: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String = "",
    /** SQLite stores 0/1 but the server emits these two as JSON booleans. */
    val drm: Boolean = false,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    val status: String = "",
    @SerialName("page_count") val pageCount: Int? = null
)

@Serializable
data class BookMetaDto(
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("published_year") val publishedYear: Int? = null,
    val authors: List<String> = emptyList(),
    /**
     * When the book's *content* last changed, as opposed to its metadata. The server bumps this
     * whenever a file is attached, which is exactly the signal "there is a newer file than the
     * one this device holds". The detail endpoint has always emitted it — the row is spread
     * whole into the response — it simply was not mapped until the sync check needed it.
     */
    @SerialName("content_updated_at") val contentUpdatedAt: String? = null
)

@Serializable
data class BookDetailDto(
    val book: BookMetaDto = BookMetaDto(),
    val files: List<BookFileDto> = emptyList()
)

@Serializable
data class BatchRequestDto(
    val bookIds: List<String>
)

@Serializable
data class BatchChangeDto(
    val id: String,
    val ok: Boolean,
    val error: String? = null
)

@Serializable
data class BatchOutcomeDto(
    val requested: Int = 0,
    val applied: Int = 0,
    val skipped: Int = 0,
    val changed: List<BatchChangeDto> = emptyList()
)
