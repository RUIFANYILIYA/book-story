/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.use_case.category

import ua.acclorite.book_story.core.log.logE
import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.domain.repository.CategoryRepository
import javax.inject.Inject

class AddCategoryUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository
) {

    /**
     * [parentId] nests the new shelf under another one; `null` creates a top-level shelf.
     *
     * Returns the id of the created shelf, or `null` when the insert failed. A sub-shelf built by
     * dragging one book onto another has to file both books into itself in the same gesture, and
     * that is impossible without the id.
     */
    suspend operator fun invoke(title: String, parentId: Int? = null): Int? {
        logI("Inserting [${title}] category.")

        return categoryRepository.addCategory(Category(title = title, parentId = parentId)).fold(
            onSuccess = {
                logI("Successfully inserted [${title}] category with id [$it].")
                it
            },
            onFailure = {
                logE("Could not insert [${title}] category with error: ${it.message}")
                null
            }
        )
    }
}
