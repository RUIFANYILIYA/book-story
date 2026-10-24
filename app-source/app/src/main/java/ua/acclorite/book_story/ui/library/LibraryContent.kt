/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import ua.acclorite.book_story.core.BottomSheet
import ua.acclorite.book_story.core.Dialog
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.CombineRequest
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.presentation.library.model.LibraryLayout
import ua.acclorite.book_story.presentation.library.model.LibrarySortOrder
import ua.acclorite.book_story.presentation.library.model.LibraryTitlePosition
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.presentation.settings.SettingsEvent

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun LibraryContent(
    books: List<SelectableBook>,
    remoteBooks: List<SelectableBook>,
    selectedItemsCount: Int,
    hasSelectedItems: Boolean,
    titlePosition: LibraryTitlePosition,
    readButton: Boolean,
    showProgress: Boolean,
    showCategoryTabs: Boolean,
    showBookCount: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    bookCount: Int,
    focusRequester: FocusRequester,
    pagerState: PagerState,
    isLoading: Boolean,
    isRefreshing: Boolean,
    doublePressExit: Boolean,
    categories: List<Category>,
    selectedSubShelfId: Int?,
    editingSubShelf: Category?,
    combine: CombineRequest?,
    showDefaultCategory: Boolean,
    perCategorySort: Boolean,
    sortOrder: LibrarySortOrder,
    sortOrderDescending: Boolean,
    layout: LibraryLayout,
    gridSize: Int,
    autoGridSize: Boolean,
    isArranging: Boolean,
    refreshState: PullRefreshState,
    dialog: Dialog?,
    bottomSheet: BottomSheet?,
    updateCategory: (SettingsEvent.OnUpdateCategory) -> Unit,
    changeSortOrder: (LibrarySortOrder) -> Unit,
    changeSortOrderDescending: (Boolean) -> Unit,
    selectBook: (LibraryEvent.OnSelectBook) -> Unit,
    searchVisibility: (LibraryEvent.OnSearchVisibility) -> Unit,
    requestFocus: (LibraryEvent.OnRequestFocus) -> Unit,
    searchQueryChange: (LibraryEvent.OnSearchQueryChange) -> Unit,
    search: (LibraryEvent.OnSearch) -> Unit,
    selectBooks: (LibraryEvent.OnSelectBooks) -> Unit,
    clearSelectedBooks: (LibraryEvent.OnClearSelectedBooks) -> Unit,
    showMoveDialog: (LibraryEvent.OnShowMoveDialog) -> Unit,
    showDeleteDialog: (LibraryEvent.OnShowDeleteDialog) -> Unit,
    actionMoveDialog: (LibraryEvent.OnActionMoveDialog) -> Unit,
    actionDeleteDialog: (LibraryEvent.OnActionDeleteDialog) -> Unit,
    dismissDialog: (LibraryEvent.OnDismissDialog) -> Unit,
    showFilterBottomSheet: (LibraryEvent.OnShowFilterBottomSheet) -> Unit,
    showCreateShelfDialog: (LibraryEvent.OnShowCreateShelfDialog) -> Unit,
    onSelectSubShelf: (LibraryEvent.OnSelectSubShelf) -> Unit,
    showCreateSubShelfDialog: (LibraryEvent.OnShowCreateSubShelfDialog) -> Unit,
    showEditSubShelfDialog: (LibraryEvent.OnShowEditSubShelfDialog) -> Unit,
    onReorderShelves: (List<Category>) -> Unit,
    createShelf: (LibraryEvent.OnCreateShelf) -> Unit,
    createSubShelf: (LibraryEvent.OnCreateSubShelf) -> Unit,
    renameSubShelf: (LibraryEvent.OnRenameSubShelf) -> Unit,
    deleteSubShelf: (LibraryEvent.OnDeleteSubShelf) -> Unit,
    onToggleArrange: (LibraryEvent.OnToggleArrange) -> Unit,
    onCombineBooks: (LibraryEvent.OnCombineBooks) -> Unit,
    onFileIntoSubShelf: (LibraryEvent.OnFileIntoSubShelf) -> Unit,
    confirmCombine: (LibraryEvent.OnConfirmCombine) -> Unit,
    dismissBottomSheet: (LibraryEvent.OnDismissBottomSheet) -> Unit,
    navigateToBrowse: (LibraryEvent.OnNavigateToBrowse) -> Unit,
    navigateToBookInfo: (LibraryEvent.OnNavigateToBookInfo) -> Unit,
    navigateToReader: (LibraryEvent.OnNavigateToReader) -> Unit,
    navigateToLibrarySettings: (LibraryEvent.OnNavigateToLibrarySettings) -> Unit,
    downloadAllBooks: () -> Unit
) {
    // Which shelf the pager is resting on. The move dialog needs it to decide whose sub-shelves to
    // list, so the page order here has to match the one the tab row and the pager use.
    val currentShelf = remember(categories, showDefaultCategory) {
        derivedStateOf {
            categories
                .filter { it.parentId == null }
                .filterNot { if (!showDefaultCategory) it.id == -1 else false }
                .sortedBy { it.order }
                .getOrNull(pagerState.currentPage)
        }
    }

    // One drag state for the whole library: it has to outlive a page swipe and a layout switch.
    val dragState = remember { LibraryDragState() }
    LaunchedEffect(isArranging) {
        // Leaving arrange mode mid-drag must not leave a cell floating.
        if (!isArranging) dragState.cancel()
    }

    LibraryDialog(
        dialog = dialog,
        books = books,
        categories = categories,
        selectedItemsCount = selectedItemsCount,
        editingSubShelf = editingSubShelf,
        combine = combine,
        currentShelfId = currentShelf.value?.id,
        actionMoveDialog = actionMoveDialog,
        actionDeleteDialog = actionDeleteDialog,
        createShelf = createShelf,
        createSubShelf = createSubShelf,
        renameSubShelf = renameSubShelf,
        deleteSubShelf = deleteSubShelf,
        confirmCombine = confirmCombine,
        dismissDialog = dismissDialog,
        navigateToLibrarySettings = navigateToLibrarySettings
    )

    LibraryBottomSheet(
        bottomSheet = bottomSheet,
        categories = categories,
        showDefaultCategory = showDefaultCategory,
        pagerState = pagerState,
        sortOrder = sortOrder,
        sortOrderDescending = sortOrderDescending,
        perCategorySort = perCategorySort,
        changeSortOrder = changeSortOrder,
        changeSortOrderDescending = changeSortOrderDescending,
        updateCategory = updateCategory,
        dismissBottomSheet = dismissBottomSheet
    )

    LibraryScaffold(
        books = books,
        remoteBooks = remoteBooks,
        selectedItemsCount = selectedItemsCount,
        hasSelectedItems = hasSelectedItems,
        titlePosition = titlePosition,
        readButton = readButton,
        showProgress = showProgress,
        showBookCount = showBookCount,
        showCategoryTabs = showCategoryTabs,
        showSearch = showSearch,
        searchQuery = searchQuery,
        bookCount = bookCount,
        focusRequester = focusRequester,
        pagerState = pagerState,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        layout = layout,
        gridSize = gridSize,
        autoGridSize = autoGridSize,
        categories = categories,
        showDefaultCategory = showDefaultCategory,
        perCategorySort = perCategorySort,
        sortOrder = sortOrder,
        sortOrderDescending = sortOrderDescending,
        isArranging = isArranging,
        dragState = dragState,
        searchVisibility = searchVisibility,
        requestFocus = requestFocus,
        searchQueryChange = searchQueryChange,
        search = search,
        selectBook = selectBook,
        selectBooks = selectBooks,
        clearSelectedBooks = clearSelectedBooks,
        showMoveDialog = showMoveDialog,
        showDeleteDialog = showDeleteDialog,
        showFilterBottomSheet = showFilterBottomSheet,
        showCreateShelfDialog = showCreateShelfDialog,
        showCreateSubShelfDialog = showCreateSubShelfDialog,
        onSelectSubShelf = onSelectSubShelf,
        showEditSubShelfDialog = showEditSubShelfDialog,
        onReorderShelves = onReorderShelves,
        onToggleArrange = { onToggleArrange(LibraryEvent.OnToggleArrange) },
        onCombineBooks = { dragged, target ->
            onCombineBooks(
                LibraryEvent.OnCombineBooks(
                    draggedId = dragged.data.id,
                    targetId = target.data.id,
                    parentId = currentShelf.value?.id,
                    subShelfIds = categories.filter { it.parentId != null }.map { it.id }
                )
            )
        },
        onFileIntoSubShelf = { book, subShelf ->
            onFileIntoSubShelf(
                LibraryEvent.OnFileIntoSubShelf(
                    bookId = book.data.id,
                    subShelfId = subShelf.id,
                    parentId = subShelf.parentId,
                    subShelfIds = categories.filter { it.parentId != null }.map { it.id }
                )
            )
        },
        refreshState = refreshState,
        navigateToBrowse = navigateToBrowse,
        navigateToBookInfo = navigateToBookInfo,
        navigateToReader = navigateToReader,
        downloadAllBooks = downloadAllBooks
    )

    // The folder sheet. It is drawn over the scaffold rather than inside it so that closing it
    // leaves the shelf exactly as it was: same page, same scroll position, same arrange mode.
    val openSubShelf = remember(selectedSubShelfId, categories) {
        categories.firstOrNull { it.id == selectedSubShelfId }
    }
    val subShelfBooks = remember(openSubShelf, books, remoteBooks) {
        val id = openSubShelf?.id
        if (id == null) emptyList()
        else (books + remoteBooks).filter { book ->
            book.data.categories.any { it == id }
        }
    }

    if (openSubShelf != null) {
        LibrarySubShelfSheet(
            subShelf = openSubShelf,
            books = subShelfBooks,
            layout = layout,
            titlePosition = titlePosition,
            gridSize = gridSize,
            autoGridSize = autoGridSize,
            readButton = readButton,
            showProgress = showProgress,
            hasSelectedItems = hasSelectedItems,
            onDismiss = { onSelectSubShelf(LibraryEvent.OnSelectSubShelf(null)) },
            onEdit = {
                onSelectSubShelf(LibraryEvent.OnSelectSubShelf(null))
                showEditSubShelfDialog(LibraryEvent.OnShowEditSubShelfDialog(openSubShelf))
            },
            selectBook = { book, select ->
                selectBook(LibraryEvent.OnSelectBook(id = book.data.id, select = select))
            },
            navigateToBookInfo = { book ->
                navigateToBookInfo(LibraryEvent.OnNavigateToBookInfo(book.data.id))
            },
            navigateToReader = { book ->
                navigateToReader(LibraryEvent.OnNavigateToReader(book.data.id))
            }
        )
    }

    LibraryBackHandler(
        hasSelectedItems = hasSelectedItems,
        showSearch = showSearch,
        isArranging = isArranging,
        insideSubShelf = selectedSubShelfId != null,
        pagerState = pagerState,
        doublePressExit = doublePressExit,
        clearSelectedBooks = clearSelectedBooks,
        searchVisibility = searchVisibility,
        onToggleArrange = onToggleArrange,
        onCloseSubShelf = { onSelectSubShelf(LibraryEvent.OnSelectSubShelf(null)) }
    )
}
