/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.mapper.bookmark

import ua.acclorite.book_story.data.local.dto.BookmarkEntity
import ua.acclorite.book_story.domain.model.reader.Bookmark
import javax.inject.Inject

class BookmarkMapperImpl @Inject constructor() : BookmarkMapper {
    override fun toBookmarkEntity(bookmark: Bookmark): BookmarkEntity {
        return BookmarkEntity(
            bookId = bookmark.bookId,
            scrollIndex = bookmark.scrollIndex,
            scrollOffset = bookmark.scrollOffset,
            preview = bookmark.preview,
            createdAt = bookmark.createdAt
        )
    }

    override fun toBookmark(bookmarkEntity: BookmarkEntity): Bookmark {
        return Bookmark(
            bookId = bookmarkEntity.bookId,
            scrollIndex = bookmarkEntity.scrollIndex,
            scrollOffset = bookmarkEntity.scrollOffset,
            preview = bookmarkEntity.preview,
            createdAt = bookmarkEntity.createdAt
        )
    }
}
