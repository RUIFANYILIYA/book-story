/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.local.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ua.acclorite.book_story.presentation.library.model.LibrarySortOrder

@Entity
data class CategoryEntity(
    @PrimaryKey(true) val id: Int = 0,
    val title: String,
    val order: Int,
    @ColumnInfo(defaultValue = "LAST_READ") val sortOrder: LibrarySortOrder,
    @ColumnInfo(defaultValue = "1") val sortOrderDescending: Boolean,
    /**
     * The shelf this one is nested under, or `null` when this is a top-level shelf.
     *
     * Only one extra level is supported on purpose: the tab row is the top level and the shelf
     * strip inside a page is the level below it, so a deeper tree would have nowhere to render.
     */
    val parentId: Int? = null
)