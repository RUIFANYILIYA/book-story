/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoveUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.common.components.common.AnimatedVisibility
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.SearchTextField
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.top_bar.TopAppBar
import ua.acclorite.book_story.ui.common.components.top_bar.TopAppBarData
import ua.acclorite.book_story.ui.navigator.NavigatorIconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(
    books: List<SelectableBook>,
    remoteBooks: List<SelectableBook>,
    selectedItemsCount: Int,
    hasSelectedItems: Boolean,
    showBookCount: Boolean,
    showCategoryTabs: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    bookCount: Int,
    focusRequester: FocusRequester,
    pagerState: PagerState,
    isLoading: Boolean,
    isRefreshing: Boolean,
    categories: List<Category>,
    showDefaultCategory: Boolean,
    isArranging: Boolean,
    searchVisibility: (LibraryEvent.OnSearchVisibility) -> Unit,
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
    onReorderShelves: (List<Category>) -> Unit,
    onToggleArrange: () -> Unit
) {
    var createMenuExpanded by remember { mutableStateOf(false) }
    val defaultCategory = stringResource(id = R.string.default_tab)
    val categoriesWithBooks = remember(
        books,
        remoteBooks,
        categories,
        showDefaultCategory,
        defaultCategory
    ) {
        derivedStateOf {
            // Catalogue tiles have no category, so they belong to the default tab's count as well.
            val shelf = books + remoteBooks
            // Sub-shelves are not tabs: they live inside their parent's page, so only top-level
            // shelves are listed here. A shelf's count includes the books filed into its folders,
            // because a folder tile is one cell holding several books and "0" next to it would be
            // a lie. The selection screen filters them back out — see contentActions below.
            categories
                .filter { it.id != -1 && it.parentId == null }
                .map { category ->
                    category to shelf.filter { book ->
                        book.data.categories.any { it == category.id }
                    }
                }
                .toMutableList().apply {
                if (showDefaultCategory) {
                    val categoryIds = categories.map { it.id }.toSet()
                    add(
                        0,
                        Category(
                            id = -1,
                            title = defaultCategory
                        ) to shelf.filter { book ->
                            book.data.categories.none { category -> category in categoryIds }
                        }
                    )
                }
            }.toList()
        }
    }
    // Every sub-shelf id: used to keep "select all" to the cells actually on screen.
    val subShelfIds = remember(categories) {
        categories.filter { it.parentId != null }.map { it.id }.toSet()
    }
    val currentCategory = remember(pagerState.currentPage, categoriesWithBooks) {
        derivedStateOf {
            categoriesWithBooks.value[pagerState.currentPage]
        }
    }

    val animatedItemCountBackgroundColor = animateColorAsState(
        if (hasSelectedItems) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )

    TopAppBar(
        scrollBehavior = null,
        isTopBarScrolled = hasSelectedItems,

        shownTopBar = when {
            hasSelectedItems -> 2
            showSearch -> 1
            isArranging -> 3
            else -> 0
        },
        topBars = listOf(
            TopAppBarData(
                contentID = 0,
                // No navigation icon: a folder is a sheet now, and it closes itself.
                contentNavigationIcon = {},
                contentTitle = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StyledText(
                            text = if (showCategoryTabs) {
                                stringResource(id = R.string.library_screen)
                            } else {
                                currentCategory.value.first.title
                            }
                        )

                        if (showBookCount) {
                            Spacer(modifier = Modifier.width(6.dp))
                            StyledText(
                                text = if (showCategoryTabs) {
                                    bookCount
                                } else {
                                    currentCategory.value.second.size
                                }.toString(),
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainer,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                style = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp
                                )
                            )
                        }
                    }
                },
                contentActions = {
                    IconButton(
                        icon = Icons.Default.Search,
                        contentDescription = R.string.search_content_desc,
                        disableOnClick = true,
                    ) {
                        searchVisibility(LibraryEvent.OnSearchVisibility(true))
                    }
                    IconButton(
                        icon = Icons.Default.FilterList,
                        contentDescription = R.string.filter_content_desc,
                        disableOnClick = false,
                    ) {
                        showFilterBottomSheet(LibraryEvent.OnShowFilterBottomSheet)
                    }

                    // Creating a shelf lives here because this is where shelves are used; the
                    // settings screen keeps its own copy of the same dialog for when you are
                    // already there.
                    //
                    // Both create actions share one button and a menu. They used to be two
                    // folder icons side by side, and once arrange mode joined the row the six
                    // icons no longer fit a 360 dp screen: the title was clipped to its count.
                    Box {
                        IconButton(
                            icon = Icons.Default.CreateNewFolder,
                            contentDescription = R.string.create_category,
                            disableOnClick = false,
                        ) {
                            createMenuExpanded = true
                        }
                        DropdownMenu(
                            expanded = createMenuExpanded,
                            onDismissRequest = { createMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    StyledText(
                                        text = stringResource(id = R.string.create_category),
                                        maxLines = 1
                                    )
                                },
                                onClick = {
                                    createMenuExpanded = false
                                    showCreateShelfDialog(LibraryEvent.OnShowCreateShelfDialog)
                                }
                            )
                            // A sub-shelf needs a real shelf to hang from. The default shelf is
                            // the "everything un-filed" bucket and cannot own one.
                            if (currentCategory.value.first.id != -1) {
                                DropdownMenuItem(
                                    text = {
                                        StyledText(
                                            text = stringResource(id = R.string.create_sub_shelf),
                                            maxLines = 1
                                        )
                                    },
                                    onClick = {
                                        createMenuExpanded = false
                                        showCreateSubShelfDialog(
                                            LibraryEvent.OnShowCreateSubShelfDialog(
                                                currentCategory.value.first.id
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                    // Dragging needs a parent shelf for the same reason a sub-shelf does.
                    if (currentCategory.value.first.id != -1) {
                        IconButton(
                            icon = Icons.Outlined.DashboardCustomize,
                            contentDescription = R.string.arrange_shelf_content_desc,
                            disableOnClick = false,
                        ) {
                            onToggleArrange()
                        }
                    }

                    NavigatorIconButton()
                }
            ),

            TopAppBarData(
                contentID = 1,
                contentNavigationIcon = {
                    IconButton(
                        icon = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = R.string.exit_search_content_desc,
                        disableOnClick = true
                    ) {
                        searchVisibility(LibraryEvent.OnSearchVisibility(false))
                    }
                },
                contentTitle = {
                    SearchTextField(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onGloballyPositioned {
                                requestFocus(LibraryEvent.OnRequestFocus)
                            },
                        initialQuery = searchQuery,
                        onQueryChange = {
                            searchQueryChange(LibraryEvent.OnSearchQueryChange(it))
                        },
                        onSearch = {
                            search(LibraryEvent.OnSearch)
                        }
                    )
                },
                contentActions = {
                    NavigatorIconButton()
                },
            ),

            TopAppBarData(
                contentID = 2,
                contentNavigationIcon = {
                    IconButton(
                        icon = Icons.Default.Clear,
                        contentDescription = R.string.clear_selected_items_content_desc,
                        disableOnClick = true
                    ) {
                        clearSelectedBooks(LibraryEvent.OnClearSelectedBooks)
                    }
                },
                contentTitle = {
                    StyledText(
                        text = stringResource(
                            id = R.string.selected_items_count_query,
                            selectedItemsCount.coerceAtLeast(1)
                        ),
                        maxLines = 1
                    )
                },
                contentActions = {
                    IconButton(
                        icon = Icons.Default.SelectAll,
                        contentDescription = R.string.select_all_books_content_desc,
                        disableOnClick = false,
                    ) {
                        selectBooks(
                            LibraryEvent.OnSelectBooks(
                                // Select-all means the cells on screen: books inside a folder are
                                // not among them, and selecting them would delete what is not shown.
                                books = currentCategory.value.second.filterNot { book ->
                                    book.data.categories.any { id -> id in subShelfIds }
                                }
                            )
                        )
                    }
                    IconButton(
                        icon = Icons.Outlined.MoveUp,
                        contentDescription = R.string.move_books_content_desc,
                        enabled = !isLoading && !isRefreshing,
                        disableOnClick = false,
                    ) {
                        showMoveDialog(LibraryEvent.OnShowMoveDialog)
                    }
                    IconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = R.string.delete_books_content_desc,
                        enabled = !isLoading && !isRefreshing,
                        disableOnClick = false
                    ) {
                        showDeleteDialog(LibraryEvent.OnShowDeleteDialog)
                    }
                }
            ),

            // Arrange mode: a mode with its own bar, so it cannot be mistaken for a normal shelf.
            TopAppBarData(
                contentID = 3,
                contentNavigationIcon = {
                    IconButton(
                        icon = Icons.Default.Check,
                        contentDescription = R.string.arrange_shelf_done_content_desc,
                        disableOnClick = true
                    ) {
                        onToggleArrange()
                    }
                },
                contentTitle = {
                    StyledText(
                        text = stringResource(id = R.string.arrange_shelf),
                        maxLines = 1
                    )
                },
                contentActions = {
                    NavigatorIconButton()
                }
            ),
        ),
        customContent = {
            AnimatedVisibility(
                visible = showCategoryTabs && !isArranging,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LibraryTabs(
                    categoriesWithBooks = categoriesWithBooks.value,
                    pagerState = pagerState,
                    itemCountBackgroundColor = animatedItemCountBackgroundColor.value,
                    showBookCount = showBookCount,
                    onReorder = onReorderShelves
                )
            }
        }
    )
}
