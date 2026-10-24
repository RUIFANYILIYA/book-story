/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.mapper.bookmark

import ua.acclorite.book_story.data.local.dto.BookmarkEntity
import ua.acclorite.book_story.domain.model.reader.Bookmark

interface BookmarkMapper {
    fun toBookmarkEntity(bookmark: Bookmark): BookmarkEntity
    fun toBookmark(bookmarkEntity: BookmarkEntity): Bookmark
}
