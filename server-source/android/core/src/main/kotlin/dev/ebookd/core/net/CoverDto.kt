package dev.ebookd.core.net

import kotlinx.serialization.Serializable

/** `POST /api/v1/books/:id/cover` — what the server stored, and where to read it back. */
@Serializable
data class CoverOverrideDto(
    val bookId: String = "",
    /** Relative, like every other cover URL. */
    val coverUrl: String = "",
    val mediaType: String = "",
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val updatedAt: String? = null,
)
