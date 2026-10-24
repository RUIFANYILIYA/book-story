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
import ua.acclorite.book_story.data.local.dto.CategoryEntity

@Dao
interface CategoryDao {
    @Query("INSERT OR IGNORE INTO CategoryEntity (id, title, `order`, sortOrder, sortOrderDescending) VALUES (-1, '', -1, 'LAST_READ', 1)")
    fun ensureDefaultCategory()

    /** Returns the row id, because the caller that just created a sub-shelf needs to file books into it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(
        category: CategoryEntity
    ): Long

    @Query("SELECT * FROM categoryentity ORDER BY `order` ASC")
    suspend fun getCategories(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categoryentity")
    suspend fun getCategoriesCount(): Int

    /** Top-level shelves only; the synthetic default shelf (id -1) has no parent either. */
    @Query("SELECT COUNT(*) FROM categoryentity WHERE parentId IS NULL")
    suspend fun getRootCategoriesCount(): Int

    @Query("SELECT * FROM categoryentity WHERE parentId = :parentId ORDER BY `order` ASC")
    suspend fun getChildCategories(
        parentId: Int
    ): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categoryentity WHERE parentId = :parentId")
    suspend fun getChildCategoriesCount(
        parentId: Int
    ): Int

    /**
     * Deleting a shelf keeps its sub-shelves: they are re-parented to the top level instead of
     * being deleted along with it.
     */
    @Query("UPDATE categoryentity SET parentId = NULL WHERE parentId = :parentId")
    suspend fun promoteChildCategories(
        parentId: Int
    )

    @Update
    suspend fun updateCategory(
        category: CategoryEntity
    )

    @Delete
    suspend fun deleteCategory(
        category: CategoryEntity
    )
}