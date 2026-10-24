/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.repository

import ua.acclorite.book_story.domain.model.reader.Bookmark

interface BookmarkRepository {
    suspend fun addBookmark(
        bookmark: Bookmark
    ): Result<Unit>

    suspend fun getBookmarks(
        bookId: Int
    ): Result<List<Bookmark>>

    suspend fun deleteBookmark(
        bookmark: Bookmark
    ): Result<Unit>
}
