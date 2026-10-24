/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.library

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.core.BottomSheet
import ua.acclorite.book_story.core.Dialog
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.model.SelectableBook

@Immutable
data class LibraryState(
    val books: List<SelectableBook> = emptyList(),

    /**
     * What the library server has that this device does not, kept apart from [books] so selecting,
     * moving and deleting stay strictly local.
     */
    val remoteBooks: List<SelectableBook> = emptyList(),

    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,

    val selectedItemsCount: Int = 0,
    val hasSelectedItems: Boolean = false,

    val showSearch: Boolean = false,
    val searchQuery: String = "",
    val hasFocused: Boolean = false,

    val dialog: Dialog? = null,
    val bottomSheet: BottomSheet? = null,

    /** The book whose long-press menu is open, if any. */
    val bookActions: SelectableBook? = null,

    /** A book waiting for the "delete from this device?" answer. */
    val pendingDelete: SelectableBook? = null,

    /** The books the rename dialogs are open for; both dialogs come from the book info page. */
    val editingTitle: SelectableBook? = null,
    val editingAuthor: SelectableBook? = null,

    /** The sub-shelf picked inside the current tab; `null` shows the tab's whole shelf. */
    val selectedSubShelfId: Int? = null,

    /** The shelf a new sub-shelf is being created under; set while the create dialog is open. */
    val subShelfParentId: Int? = null,

    /** The sub-shelf the rename/delete dialog is open for. */
    val editingSubShelf: Category? = null,

    /**
     * Arrange mode: long-press drags a book instead of opening its actions, and dropping one book
     * onto another turns the pair into a sub-shelf.
     */
    val isArranging: Boolean = false,

    /** The pair waiting for a name; set while the combine dialog is open. */
    val combine: CombineRequest? = null
)

/**
 * Everything the combine dialog needs to finish the job after the name is typed: the two books
 * that were put together, the shelf the new sub-shelf hangs from, and which ids are sub-shelves —
 * the model has no category tree of its own, and a book may only sit in one sub-shelf.
 */
@Immutable
data class CombineRequest(
    val dragged: SelectableBook,
    val target: SelectableBook,
    val parentId: Int,
    val subShelfIds: List<Int>
)
