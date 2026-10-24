/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.data.local.room.BookDatabase
import ua.acclorite.book_story.data.mapper.bookmark.BookmarkMapper
import ua.acclorite.book_story.domain.model.reader.Bookmark
import ua.acclorite.book_story.domain.repository.BookmarkRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val database: BookDatabase,
    private val bookmarkMapper: BookmarkMapper
) : BookmarkRepository {

    override suspend fun addBookmark(bookmark: Bookmark): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            database.bookmarkDao.insertBookmark(
                bookmarkMapper.toBookmarkEntity(bookmark)
            )
        }
    }

    override suspend fun getBookmarks(bookId: Int): Result<List<Bookmark>> = runCatching {
        withContext(Dispatchers.IO) {
            database.bookmarkDao.getBookmarksForBook(bookId).map {
                bookmarkMapper.toBookmark(it)
            }
        }
    }

    override suspend fun deleteBookmark(bookmark: Bookmark): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            database.bookmarkDao.deleteBookmark(
                bookmarkMapper.toBookmarkEntity(bookmark)
            )
        }
    }
}
