/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.repository

import ua.acclorite.book_story.domain.model.library.Category

interface CategoryRepository {
    /** Returns the id of the row it just created: a sub-shelf built by dragging books into each
     *  other has to file those books into itself in the same gesture. */
    suspend fun addCategory(
        category: Category
    ): Result<Int>

    suspend fun getCategories(): Result<List<Category>>

    /** The shelves nested directly under [parentId], in their own order. */
    suspend fun getChildCategories(
        parentId: Int
    ): Result<List<Category>>

    suspend fun updateCategory(
        category: Category
    ): Result<Unit>

    suspend fun updateOrder(
        categories: List<Category>
    ): Result<Unit>

    suspend fun deleteCategory(
        category: Category
    ): Result<Unit>
}