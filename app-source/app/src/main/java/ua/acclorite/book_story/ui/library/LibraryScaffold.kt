/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.presentation.library.model.LibraryLayout
import ua.acclorite.book_story.presentation.library.model.LibrarySortOrder
import ua.acclorite.book_story.presentation.library.model.LibraryTitlePosition
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.common.components.common.AnimatedVisibility
import ua.acclorite.book_story.ui.common.components.common.StyledText

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun LibraryScaffold(
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
    refreshState: PullRefreshState,
    layout: LibraryLayout,
    gridSize: Int,
    autoGridSize: Boolean,
    categories: List<Category>,
    showDefaultCategory: Boolean,
    perCategorySort: Boolean,
    sortOrder: LibrarySortOrder,
    sortOrderDescending: Boolean,
    isArranging: Boolean,
    dragState: LibraryDragState,
    searchVisibility: (LibraryEvent.OnSearchVisibility) -> Unit,
    selectBook: (LibraryEvent.OnSelectBook) -> Unit,
    requestFocus: (LibraryEvent.OnRequestFocus) -> Unit,
    searchQueryChange: (LibraryEvent.OnSearchQueryChange) -> Unit,
    search: (LibraryEvent.OnSearch) -> Unit,
    selectBooks: (LibraryEvent.OnSelectBooks) -> Unit,
    clearSelectedBooks: (LibraryEvent.OnClearSelectedBooks) -> Unit,
    showMoveDialog: (LibraryEvent.OnShowMoveDialog) -> Unit,
    showDeleteDialog: (LibraryEvent.OnShowDeleteDialog) -> Unit,
    showFilterBottomSheet: (LibraryEvent.OnShowFilterBottomSheet) -> Unit,
    showCreateShelfDialog: (LibraryEvent.OnShowCreateShelfDialog) -> Unit,
    showCreateSubShelfDialog: (LibraryEvent.OnShowCreateSubShelfDialog) -> Unit,
    onSelectSubShelf: (LibraryEvent.OnSelectSubShelf) -> Unit,
    showEditSubShelfDialog: (LibraryEvent.OnShowEditSubShelfDialog) -> Unit,
    onReorderShelves: (List<Category>) -> Unit,
    onToggleArrange: () -> Unit,
    onCombineBooks: (dragged: SelectableBook, target: SelectableBook) -> Unit,
    onFileIntoSubShelf: (book: SelectableBook, subShelf: Category) -> Unit,
    navigateToBrowse: (LibraryEvent.OnNavigateToBrowse) -> Unit,
    navigateToBookInfo: (LibraryEvent.OnNavigateToBookInfo) -> Unit,
    navigateToReader: (LibraryEvent.OnNavigateToReader) -> Unit,
    downloadAllBooks: () -> Unit,
) {
    // Which shelf the pager is resting on decides which sub-shelves belong to it.
    val currentShelf = remember(categories, showDefaultCategory) {
        derivedStateOf {
            categories
                .filter { it.parentId == null }
                .filterNot { if (!showDefaultCategory) it.id == -1 else false }
                .sortedBy { it.order }
                .getOrNull(pagerState.currentPage)
        }
    }
    val currentSubShelves = remember(categories, currentShelf) {
        derivedStateOf {
            val shelf = currentShelf.value
            // The default shelf collects everything un-filed, so it has no sub-shelves to show.
            if (shelf == null || shelf.id == -1) emptyList()
            else categories
                .filter { it.parentId == shelf.id }
                .sortedBy { it.order }
        }
    }
    Scaffold(
        Modifier
            .fillMaxSize()
            .pullRefresh(refreshState),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LibraryTopBar(
                books = books,
                remoteBooks = remoteBooks,
                selectedItemsCount = selectedItemsCount,
                hasSelectedItems = hasSelectedItems,
                showBookCount = showBookCount,
                showCategoryTabs = showCategoryTabs,
                showSearch = showSearch,
                searchQuery = searchQuery,
                bookCount = bookCount,
                focusRequester = focusRequester,
                pagerState = pagerState,
                isLoading = isLoading,
                isRefreshing = isRefreshing,
                categories = categories,
                showDefaultCategory = showDefaultCategory,
                isArranging = isArranging,
                searchVisibility = searchVisibility,
                requestFocus = requestFocus,
                searchQueryChange = searchQueryChange,
                search = search,
                selectBooks = selectBooks,
                clearSelectedBooks = clearSelectedBooks,
                showMoveDialog = showMoveDialog,
                showDeleteDialog = showDeleteDialog,
                showFilterBottomSheet = showFilterBottomSheet,
                showCreateShelfDialog = showCreateShelfDialog,
                showCreateSubShelfDialog = showCreateSubShelfDialog,
                onReorderShelves = onReorderShelves,
                onToggleArrange = onToggleArrange
            )
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            LibraryDownloadBanner(
                missingCount = remoteBooks.size,
                downloadAll = downloadAllBooks
            )

            // Arrange mode explains itself once, where the sub-shelf strip used to be: dragging one
            // book onto another is not a gesture people guess.
            AnimatedVisibility(
                visible = isArranging,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LibraryArrangeBanner()
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LibraryPager(
                    books = books,
                    remoteBooks = remoteBooks,
                    pagerState = pagerState,
                    categories = categories,
                    showDefaultCategory = showDefaultCategory,
                    perCategorySort = perCategorySort,
                    sortOrder = sortOrder,
                    sortOrderDescending = sortOrderDescending,
                    layout = layout,
                    gridSize = gridSize,
                    autoGridSize = autoGridSize,
                    hasSelectedItems = hasSelectedItems,
                    titlePosition = titlePosition,
                    readButton = readButton,
                    showProgress = showProgress,
                    isLoading = isLoading,
                    isRefreshing = isRefreshing,
                    dragState = dragState,
                    dragEnabled = isArranging,
                    onCombineBooks = onCombineBooks,
                    onFileIntoSubShelf = onFileIntoSubShelf,
                    selectBook = selectBook,
                    navigateToBrowse = navigateToBrowse,
                    navigateToReader = navigateToReader,
                    navigateToBookInfo = navigateToBookInfo,
                    openSubShelf = { onSelectSubShelf(LibraryEvent.OnSelectSubShelf(it.id)) },
                    editSubShelf = { showEditSubShelfDialog(LibraryEvent.OnShowEditSubShelfDialog(it)) }
                )

                LibraryRefreshIndicator(
                    isRefreshing = isRefreshing,
                    refreshState = refreshState
                )
            }
        }
    }
}

@Composable
private fun LibraryArrangeBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.TouchApp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        StyledText(
            text = stringResource(id = R.string.arrange_shelf_hint),
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            maxLines = 2
        )
    }
}
