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

class GetBookmarksUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository
) {

    suspend operator fun invoke(bookId: Int): List<Bookmark> {
        logI("Getting bookmarks for book [$bookId].")

        return bookmarkRepository.getBookmarks(bookId).fold(
            onSuccess = {
                logI("Successfully got [${it.size}] bookmarks.")
                it
            },
            onFailure = {
                logE("Could not get bookmarks with error: ${it.message}")
                emptyList()
            }
        )
    }
}
