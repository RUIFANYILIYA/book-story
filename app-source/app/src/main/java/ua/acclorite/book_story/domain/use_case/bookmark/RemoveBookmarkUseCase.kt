/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.use_case.bookmark

import ua.acclorite.book_story.core.log.logE
import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.domain.model.reader.Bookmark
import ua.acclorite.book_story.domain.repository.BookmarkRepository
import javax.inject.Inject

class RemoveBookmarkUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository
) {

    suspend operator fun invoke(bookmark: Bookmark): Boolean {
        logI("Removing bookmark at [${bookmark.scrollIndex}] for book [${bookmark.bookId}].")

        return bookmarkRepository.deleteBookmark(bookmark).fold(
            onSuccess = {
                logI("Successfully removed bookmark.")
                true
            },
            onFailure = {
                logE("Could not remove bookmark with error: ${it.message}")
                false
            }
        )
    }
}
