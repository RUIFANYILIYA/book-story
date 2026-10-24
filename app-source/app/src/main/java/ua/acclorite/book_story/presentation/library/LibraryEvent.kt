/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.library

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.core.ui.UIText
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.model.SelectableBook

@Immutable
sealed class LibraryEvent {
    data class OnRefreshList(
        val loading: Boolean,
        val hideSearch: Boolean
    ) : LibraryEvent()

    data class OnSearchVisibility(
        val show: Boolean
    ) : LibraryEvent()

    data class OnSearchQueryChange(
        val query: String
    ) : LibraryEvent()

    data object OnSearch : LibraryEvent()

    data object OnRequestFocus : LibraryEvent()

    data object OnClearSelectedBooks : LibraryEvent()

    data class OnSelectBook(
        val id: Int,
        val select: Boolean? = null
    ) : LibraryEvent()

    data class OnSelectBooks(
        val books: List<SelectableBook>
    ) : LibraryEvent()

    data object OnShowMoveDialog : LibraryEvent()

    data class OnActionMoveDialog(
        val selectedCategories: List<Category>
    ) : LibraryEvent()

    data object OnShowDeleteDialog : LibraryEvent()

    /** Creating a shelf used to be three screens away, in settings; this is the same dialog, here. */
    data object OnShowCreateShelfDialog : LibraryEvent()

    data class OnCreateShelf(
        val title: String
    ) : LibraryEvent()

    /* Sub-shelves: a shelf nested one level under the shelf the current tab shows. - - - - - - - */

    /** Picking a sub-shelf inside the current tab; `null` goes back to the whole shelf. */
    data class OnSelectSubShelf(
        val id: Int?
    ) : LibraryEvent()

    data class OnShowCreateSubShelfDialog(
        val parentId: Int
    ) : LibraryEvent()

    data class OnCreateSubShelf(
        val title: String
    ) : LibraryEvent()

    data class OnShowEditSubShelfDialog(
        val subCategory: Category
    ) : LibraryEvent()

    data class OnRenameSubShelf(
        val title: String
    ) : LibraryEvent()

    data object OnDeleteSubShelf : LibraryEvent()

    /* Arrange mode: drag one book onto another and the pair becomes a sub-shelf. - - - - - - - */

    data object OnToggleArrange : LibraryEvent()

    data class OnCombineBooks(
        val draggedId: Int,
        val targetId: Int,
        /** The shelf the new sub-shelf hangs from; `null` on the default tab, which has none. */
        val parentId: Int?,
        /** Every existing sub-shelf id, so the pair leaves whichever ones they were in. */
        val subShelfIds: List<Int>
    ) : LibraryEvent()

    data class OnConfirmCombine(
        val title: String
    ) : LibraryEvent()

    /** Dropped a book onto an existing folder: file it there instead of making a new one. */
    data class OnFileIntoSubShelf(
        val bookId: Int,
        val subShelfId: Int,
        val parentId: Int?,
        val subShelfIds: List<Int>
    ) : LibraryEvent()

    /* - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - */

    /** Long-press on one book: the actions that apply to that book, as a bottom sheet. */
    data class OnShowBookActions(
        val book: SelectableBook
    ) : LibraryEvent()

    data object OnDismissBookActions : LibraryEvent()

    data class OnTogglePinned(
        val book: SelectableBook
    ) : LibraryEvent()

    /** Renaming a book from the long-press menu, without opening it first. */
    data class OnShowEditTitleDialog(
        val book: SelectableBook
    ) : LibraryEvent()

    data class OnActionEditTitle(
        val title: String
    ) : LibraryEvent()

    data class OnShowEditAuthorDialog(
        val book: SelectableBook
    ) : LibraryEvent()

    data class OnActionEditAuthor(
        val author: UIText
    ) : LibraryEvent()

    data object OnDismissEditDialog : LibraryEvent()

    /** Deleting one book from this device, leaving the server's copy alone. */
    data class OnDeleteLocalBook(
        val book: SelectableBook
    ) : LibraryEvent()

    data object OnActionDeleteLocalBook : LibraryEvent()

    data object OnDismissDeleteLocal : LibraryEvent()

    /** From the long-press menu: turn the gesture into the selection mode it used to mean. */
    data class OnSelectMultiple(
        val id: Int
    ) : LibraryEvent()

    data class OnMoveBook(
        val book: SelectableBook
    ) : LibraryEvent()

    data object OnActionDeleteDialog : LibraryEvent()

    data object OnDismissDialog : LibraryEvent()

    data object OnShowFilterBottomSheet : LibraryEvent()

    /** Queue every server book that is not on the device yet. */
    data object OnDownloadAll : LibraryEvent()

    data object OnDismissBottomSheet : LibraryEvent()

    data object OnNavigateToLibrarySettings : LibraryEvent()

    data object OnNavigateToBrowse : LibraryEvent()

    data class OnNavigateToBookInfo(
        val id: Int
    ) : LibraryEvent()

    data class OnNavigateToReader(
        val id: Int
    ) : LibraryEvent()
}