/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.model.remote

import androidx.compose.runtime.Immutable

enum class RemoteDownloadPhase {
    QUEUED,
    RUNNING,
    /** Kept in the map so the tile can offer a retry instead of silently vanishing. */
    FAILED
}

@Immutable
data class RemoteDownload(
    val phase: RemoteDownloadPhase,
    /** 0f while queued or after a failure, 0f..1f while running. */
    val progress: Float
)
