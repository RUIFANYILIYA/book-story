/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.local.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.acclorite.book_story.data.local.dto.BookmarkEntity

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(
        bookmark: BookmarkEntity
    )

    @Delete
    suspend fun deleteBookmark(
        bookmark: BookmarkEntity
    )

    @Query("SELECT * FROM BookmarkEntity WHERE bookId = :bookId ORDER BY scrollIndex ASC, scrollOffset ASC")
    suspend fun getBookmarksForBook(
        bookId: Int
    ): List<BookmarkEntity>
}
