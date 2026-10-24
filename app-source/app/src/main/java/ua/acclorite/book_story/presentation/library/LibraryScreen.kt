/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.library

import android.os.Parcelable
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.presentation.settings.SettingsEvent
import ua.acclorite.book_story.presentation.settings.SettingsModel
import ua.acclorite.book_story.ui.book_info.BookInfoAuthorDialog
import ua.acclorite.book_story.ui.book_info.BookInfoTitleDialog
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.library.LibraryBookActionsSheet
import ua.acclorite.book_story.ui.library.LibraryContent
import ua.acclorite.book_story.ui.library.LibraryDeleteLocalDialog
import ua.acclorite.book_story.ui.library.LibraryEffects

@Parcelize
object LibraryScreen : Screen, Parcelable {

    @IgnoredOnParcel
    const val MOVE_DIALOG = "move_dialog"

    @IgnoredOnParcel
    const val DELETE_DIALOG = "delete_dialog"

    @IgnoredOnParcel
    const val CREATE_SHELF_DIALOG = "create_shelf_dialog"

    @IgnoredOnParcel
    const val CREATE_SUB_SHELF_DIALOG = "create_sub_shelf_dialog"

    @IgnoredOnParcel
    const val EDIT_SUB_SHELF_DIALOG = "edit_sub_shelf_dialog"

    /** Opened by dropping one book onto another in arrange mode: asks what the pair is called. */
    @IgnoredOnParcel
    const val COMBINE_DIALOG = "combine_dialog"

    @IgnoredOnParcel
    const val FILTER_BOTTOM_SHEET = "filter_bottom_sheet"

    @IgnoredOnParcel
    val refreshListChannel: Channel<Long> = Channel(Channel.CONFLATED)

    @IgnoredOnParcel
    val scrollToPageCompositionChannel: Channel<Int> = Channel(Channel.CONFLATED)

    @IgnoredOnParcel
    private var initialPage = 0

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    override fun Content() {
        val screenModel = hiltViewModel<LibraryModel>()
        val settingsModel = hiltViewModel<SettingsModel>()
        val settings = LocalSettings.current

        val state = screenModel.state.collectAsStateWithLifecycle()
        val settingsState = settingsModel.state.collectAsStateWithLifecycle()

        val showDefaultCategory = remember(
            state.value.books,
            state.value.remoteBooks,
            settingsState.value.categories,
            settings.libraryShowDefaultTab.value
        ) {
            derivedStateOf {
                val categories = settingsState.value.categories.filterNot { it.id == -1 }
                val categoryIds = categories.map { it.id }.toSet()
                // Catalogue tiles have no category of their own, so they need the default tab.
                state.value.remoteBooks.isNotEmpty() ||
                        state.value.books.any { book ->
                            book.data.categories.none { category -> category in categoryIds }
                        } || categories.isEmpty() || settings.libraryShowDefaultTab.lastValue
            }
        }

        val focusRequester = remember { FocusRequester() }
        val refreshState = rememberPullRefreshState(
            refreshing = state.value.isRefreshing,
            onRefresh = {
                screenModel.onEvent(
                    LibraryEvent.OnRefreshList(
                        loading = false,
                        hideSearch = true
                    )
                )
            }
        )

        val pagerState = rememberPagerState(
            initialPage = initialPage
        ) {
            // Only top-level shelves are pages: sub-shelves render inside their parent, so counting
            // them here would add empty pages to the pager.
            settingsState.value.categories
                .count { it.parentId == null }
                .minus(if (!showDefaultCategory.value) 1 else 0)
        }
        DisposableEffect(Unit) { onDispose { initialPage = pagerState.currentPage } }

        // A sub-shelf belongs to exactly one shelf, so moving to another tab drops the filter.
        LaunchedEffect(pagerState.currentPage) {
            screenModel.onEvent(LibraryEvent.OnSelectSubShelf(null))
        }

        LaunchedEffect(Unit) {
            scrollToPageCompositionChannel.receiveAsFlow().collectLatest {
                pagerState.scrollToPage(it)
            }
        }

        LibraryEffects(
            effects = screenModel.effects,
            focusRequester = focusRequester
        )

        LibraryContent(
            books = state.value.books,
            remoteBooks = state.value.remoteBooks,
            selectedItemsCount = state.value.selectedItemsCount,
            hasSelectedItems = state.value.hasSelectedItems,
            titlePosition = settings.libraryTitlePosition.value,
            readButton = settings.libraryShowReadButton.value,
            showProgress = settings.libraryShowProgress.value,
            showCategoryTabs = settings.libraryShowCategoryTabs.value,
            showBookCount = settings.libraryShowBookCount.value,
            showSearch = state.value.showSearch,
            searchQuery = state.value.searchQuery,
            bookCount = state.value.books.count() + state.value.remoteBooks.count(),
            focusRequester = focusRequester,
            pagerState = pagerState,
            isLoading = state.value.isLoading,
            isRefreshing = state.value.isRefreshing,
            doublePressExit = settings.doublePressExit.value,
            layout = settings.libraryLayout.value,
            gridSize = settings.libraryGridSize.value,
            autoGridSize = settings.libraryAutoGridSize.value,
            categories = settingsState.value.categories,
            selectedSubShelfId = state.value.selectedSubShelfId,
            editingSubShelf = state.value.editingSubShelf,
            combine = state.value.combine,
            showDefaultCategory = showDefaultCategory.value,
            sortOrder = settings.librarySortOrder.value,
            sortOrderDescending = settings.librarySortOrderDescending.value,
            perCategorySort = settings.libraryPerCategorySort.value,
            isArranging = state.value.isArranging,
            refreshState = refreshState,
            dialog = state.value.dialog,
            bottomSheet = state.value.bottomSheet,
            updateCategory = settingsModel::onEvent,
            changeSortOrder = { settings.librarySortOrder.update(it) },
            changeSortOrderDescending = { settings.librarySortOrderDescending.update(it) },
            selectBook = screenModel::onEvent,
            searchVisibility = screenModel::onEvent,
            requestFocus = screenModel::onEvent,
            searchQueryChange = screenModel::onEvent,
            search = screenModel::onEvent,
            selectBooks = screenModel::onEvent,
            clearSelectedBooks = screenModel::onEvent,
            showMoveDialog = screenModel::onEvent,
            actionMoveDialog = screenModel::onEvent,
            actionDeleteDialog = screenModel::onEvent,
            showDeleteDialog = screenModel::onEvent,
            showFilterBottomSheet = screenModel::onEvent,
            showCreateShelfDialog = screenModel::onEvent,
            onSelectSubShelf = screenModel::onEvent,
            showCreateSubShelfDialog = screenModel::onEvent,
            showEditSubShelfDialog = screenModel::onEvent,
            onReorderShelves = { settingsModel.onEvent(SettingsEvent.OnUpdateCategoryOrder(it)) },
            createShelf = screenModel::onEvent,
            createSubShelf = screenModel::onEvent,
            renameSubShelf = screenModel::onEvent,
            deleteSubShelf = screenModel::onEvent,
            onToggleArrange = screenModel::onEvent,
            onCombineBooks = screenModel::onEvent,
            onFileIntoSubShelf = screenModel::onEvent,
            confirmCombine = screenModel::onEvent,
            dismissBottomSheet = screenModel::onEvent,
            dismissDialog = screenModel::onEvent,
            navigateToBrowse = screenModel::onEvent,
            navigateToReader = screenModel::onEvent,
            navigateToBookInfo = screenModel::onEvent,
            navigateToLibrarySettings = screenModel::onEvent,
            downloadAllBooks = { screenModel.onEvent(LibraryEvent.OnDownloadAll) }
        )

        state.value.bookActions?.let { book ->
            LibraryBookActionsSheet(
                book = book,
                onEditTitle = { screenModel.onEvent(LibraryEvent.OnShowEditTitleDialog(it)) },
                onEditAuthor = { screenModel.onEvent(LibraryEvent.OnShowEditAuthorDialog(it)) },
                onTogglePinned = { screenModel.onEvent(LibraryEvent.OnTogglePinned(it)) },
                onMove = { screenModel.onEvent(LibraryEvent.OnMoveBook(it)) },
                onDeleteLocal = { screenModel.onEvent(LibraryEvent.OnDeleteLocalBook(it)) },
                onSelectMultiple = { screenModel.onEvent(LibraryEvent.OnSelectMultiple(it.data.id)) },
                onDismiss = { screenModel.onEvent(LibraryEvent.OnDismissBookActions) }
            )
        }

        state.value.editingTitle?.let { book ->
            BookInfoTitleDialog(
                book = book.data,
                actionTitleDialog = { screenModel.onEvent(LibraryEvent.OnActionEditTitle(it.title)) },
                dismissDialog = { screenModel.onEvent(LibraryEvent.OnDismissEditDialog) }
            )
        }

        state.value.editingAuthor?.let { book ->
            BookInfoAuthorDialog(
                book = book.data,
                actionAuthorDialog = { screenModel.onEvent(LibraryEvent.OnActionEditAuthor(it.author)) },
                dismissDialog = { screenModel.onEvent(LibraryEvent.OnDismissEditDialog) }
            )
        }

        state.value.pendingDelete?.let { book ->
            LibraryDeleteLocalDialog(
                bookTitle = book.data.title,
                actionDeleteLocal = screenModel::onEvent,
                dismissDialog = screenModel::onEvent
            )
        }
    }
}