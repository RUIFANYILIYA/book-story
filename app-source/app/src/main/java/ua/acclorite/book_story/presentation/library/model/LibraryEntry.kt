/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.library.model

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.domain.model.library.Category

/**
 * One cell of a shelf page.
 *
 * A sub-shelf occupies exactly one cell, the same as a book — that is the whole point of the
 * folder tile: it is not a strip above the grid, it is a thing in the grid. The two kinds share
 * one list so the lazy grid lays them out in a single flow and a drag can tell them apart by key
 * alone.
 */
@Immutable
sealed interface LibraryEntry {

    /** Unique across both kinds: book ids and category ids come from different tables. */
    val key: String

    @Immutable
    data class BookEntry(
        val book: SelectableBook
    ) : LibraryEntry {
        override val key: String get() = bookKey(book.data.id)
    }

    @Immutable
    data class SubShelfEntry(
        val category: Category,
        /** Up to nine covers, in shelf order, for the 3x3 wall drawn on the tile. */
        val previews: List<SelectableBook>,
        val bookCount: Int
    ) : LibraryEntry {
        override val key: String get() = subShelfKey(category.id)
    }

    companion object {
        private const val BOOK_PREFIX = "entry:b:"
        private const val SUB_SHELF_PREFIX = "entry:s:"

        fun bookKey(id: Int) = "$BOOK_PREFIX$id"
        fun subShelfKey(id: Int) = "$SUB_SHELF_PREFIX$id"

        /** Parses a drag key back into the id it was built from; `null` when it is not ours. */
        fun bookIdOf(key: String): Int? =
            key.removePrefix(BOOK_PREFIX).toIntOrNull().takeIf { key.startsWith(BOOK_PREFIX) }

        fun subShelfIdOf(key: String): Int? =
            key.removePrefix(SUB_SHELF_PREFIX).toIntOrNull().takeIf { key.startsWith(SUB_SHELF_PREFIX) }
    }
}
