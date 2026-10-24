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
import androidx.room.Update
import ua.acclorite.book_story.data.local.dto.BookEntity

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(
        book: BookEntity
    )

    @Query(
        """
        SELECT * FROM bookentity
        WHERE LOWER(title) LIKE '%' || LOWER(:query) || '%'
    """
    )
    suspend fun searchBooks(query: String): List<BookEntity>

    @Query("SELECT * FROM bookentity WHERE id=:id")
    suspend fun findBookById(id: Int): BookEntity?

    @Query("SELECT * FROM bookentity WHERE serverBookId=:serverBookId LIMIT 1")
    suspend fun findByServerBookId(serverBookId: String): BookEntity?

    @Query("SELECT * FROM bookentity WHERE serverBookId IS NOT NULL")
    suspend fun findSyncedBooks(): List<BookEntity>

    @Query(
        """
        UPDATE bookentity SET progress = :progress, scrollIndex = :scrollIndex,
            scrollOffset = :scrollOffset
        WHERE id = :id
    """
    )
    suspend fun updateProgress(id: Int, progress: Float, scrollIndex: Int, scrollOffset: Int)

    @Delete
    suspend fun deleteBook(book: BookEntity): Int

    @Update
    suspend fun updateBook(book: BookEntity): Int
}