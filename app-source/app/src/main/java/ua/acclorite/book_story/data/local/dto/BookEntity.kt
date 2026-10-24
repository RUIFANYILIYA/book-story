/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.local.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import ua.acclorite.book_story.data.converter.CategoryConverter

@Entity
@TypeConverters(CategoryConverter::class)
data class BookEntity(
    @PrimaryKey(true) val id: Int = 0,
    val title: String,
    val author: String,
    val description: String?,
    val filePath: String,
    val scrollIndex: Int,
    val scrollOffset: Int,
    val progress: Float,
    val image: String? = null,
    @ColumnInfo(defaultValue = "[]") val categories: List<Int>,
    /** Pinned books sort ahead of everything else in the library, whatever the sort order is. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Set on books that came from a library server; null for books picked from storage. */
    val serverBookId: String? = null,
    /**
     * The server's `content_updated_at` as of the last sync of this book.
     *
     * This is what makes "the server has a newer file than the one on this device" answerable
     * without downloading anything: a value that differs from the catalogue's means the bytes
     * changed since this device last took them.
     */
    val serverContentUpdatedAt: String? = null
)