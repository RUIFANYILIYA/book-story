/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.model.reader

import androidx.compose.runtime.Immutable

@Immutable
data class Bookmark(
    val bookId: Int,
    val scrollIndex: Int,
    val scrollOffset: Int,
    val preview: String,
    val createdAt: Long
)
