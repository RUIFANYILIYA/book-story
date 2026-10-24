/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.local.dto

import androidx.room.Entity

@Entity(primaryKeys = ["bookId", "scrollIndex"])
data class BookmarkEntity(
    val bookId: Int,
    val scrollIndex: Int,
    val scrollOffset: Int,
    val preview: String,
    val createdAt: Long
)
