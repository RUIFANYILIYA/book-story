/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ua.acclorite.book_story.core.helpers.compareByWithOrder
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.presentation.library.model.LibraryEntry
import ua.acclorite.book_story.presentation.library.model.LibraryLayout
import ua.acclorite.book_story.presentation.library.model.LibrarySortOrder
import ua.acclorite.book_story.presentation.library.model.LibraryTitlePosition
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.theme.DefaultTransition

/** One page of the pager: the shelf it shows and the cells it lays out. */
data class LibraryPage(
    val category: Category,
    val entries: List<LibraryEntry>
)

@Composable
fun LibraryPager(
    books: List<SelectableBook>,
    remoteBooks: List<SelectableBook>,
    pagerState: PagerState,
    categories: List<Category>,
    showDefaultCategory: Boolean,
    perCategorySort: Boolean,
    sortOrder: LibrarySortOrder,
    sortOrderDescending: Boolean,
    layout: LibraryLayout,
    gridSize: Int,
    autoGridSize: Boolean,
    hasSelectedItems: Boolean,
    titlePosition: LibraryTitlePosition,
    readButton: Boolean,
    showProgress: Boolean,
    isLoading: Boolean,
    isRefreshing: Boolean,
    dragState: LibraryDragState,
    dragEnabled: Boolean,
    onCombineBooks: (dragged: SelectableBook, target: SelectableBook) -> Unit,
    onFileIntoSubShelf: (book: SelectableBook, subShelf: Category) -> Unit,
    selectBook: (LibraryEvent.OnSelectBook) -> Unit,
    navigateToBrowse: (LibraryEvent.OnNavigateToBrowse) -> Unit,
    navigateToBookInfo: (LibraryEvent.OnNavigateToBookInfo) -> Unit,
    navigateToReader: (LibraryEvent.OnNavigateToReader) -> Unit,
    openSubShelf: (Category) -> Unit,
    editSubShelf: (Category) -> Unit
) {
    val pages = remember(
        books,
        remoteBooks,
        categories,
        perCategorySort,
        sortOrder,
        sortOrderDescending,
        showDefaultCategory
    ) {
        derivedStateOf {
            fun List<SelectableBook>.sortBooks(
                librarySortOrder: LibrarySortOrder,
                librarySortOrderDescending: Boolean
            ): List<SelectableBook> {
                // Pinned books come first under every sort order: that is the whole point of pinning
                // one, and a pin that only worked under one ordering would be a trap.
                return sortedWith(
                    compareByDescending<SelectableBook> { it.data.pinned }
                        .then(
                            compareByWithOrder(librarySortOrderDescending) { book ->
                                when (librarySortOrder) {
                                    LibrarySortOrder.NAME -> book.data.title.trim()
                                    LibrarySortOrder.LAST_READ -> book.data.lastOpened
                                    LibrarySortOrder.PROGRESS -> book.data.progress
                                    LibrarySortOrder.AUTHOR -> book.data.author.getAsString()
                                }
                            }
                        )
                )
            }

            // Catalogue tiles belong to no category, so they surface in the default one.
            val shelf = books + remoteBooks

            // Sub-shelves count as "categorised" too, so the default tab keeps its meaning.
            val categoryIds = categories.map { it.id }.toSet()
            val subShelvesByParent = categories
                .filter { it.parentId != null }
                .sortedBy { it.order }
                .groupBy { it.parentId }

            val globalSorted = shelf.sortBooks(sortOrder, sortOrderDescending)

            fun buildEntries(category: Category): List<LibraryEntry> {
                val source = if (perCategorySort) {
                    shelf.sortBooks(category.sortOrder, category.sortOrderDescending)
                } else {
                    globalSorted
                }

                // Catalogue tiles belong to no category, so they surface in the default one.
                if (category.id == -1) {
                    return source
                        .filter { book -> book.data.categories.none { it in categoryIds } }
                        .map { book -> LibraryEntry.BookEntry(book) }
                }

                // A book filed one level down shows up in its sub-shelf and nowhere else, so the
                // shelf's own list drops anything that has a folder of its own.
                val subShelves = subShelvesByParent[category.id].orEmpty()
                val subShelfIds = subShelves.map { it.id }.toSet()

                val folders = subShelves.map { subShelf ->
                    val inside = source.filter { book -> book.data.categories.any { it == subShelf.id } }
                    LibraryEntry.SubShelfEntry(
                        category = subShelf,
                        // Nine: the tile draws a 3x3 wall of the books inside it.
                        previews = inside.take(9),
                        bookCount = inside.size
                    )
                }

                val loose = source
                    .filter { book ->
                        book.data.categories.any { it == category.id } &&
                                book.data.categories.none { it in subShelfIds }
                    }
                    .map { book -> LibraryEntry.BookEntry(book) }

                // Folders lead the page: they are the coarser unit, and a folder that sorted in
                // among books by last-read time would move every time a book inside it was opened.
                return folders + loose
            }

            categories
                .filter { it.parentId == null }
                .filterNot { if (!showDefaultCategory) it.id == -1 else false }
                .sortedBy { it.order }
                .map { category -> LibraryPage(category, buildEntries(category)) }
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
        val page = remember(pages, index) {
            derivedStateOf { pages.value[index] }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            DefaultTransition(visible = !isLoading) {
                val entries = page.value.entries

                LibraryLayout(
                    entries = entries,
                    gridSize = gridSize,
                    autoGridSize = autoGridSize,
                    layout = layout,
                    dragState = dragState,
                    dragEnabled = dragEnabled,
                    onDrop = { drop ->
                        val dragged = entries.firstOrNull { it.key == drop.draggedKey }
                        val target = entries.firstOrNull { it.key == drop.targetKey }

                        // Only a book can be dragged onto something: dropping a folder onto a book
                        // would have no meaning (a sub-shelf cannot live inside another one).
                        if (dragged is LibraryEntry.BookEntry) {
                            when (target) {
                                is LibraryEntry.BookEntry -> onCombineBooks(dragged.book, target.book)
                                is LibraryEntry.SubShelfEntry -> onFileIntoSubShelf(
                                    dragged.book,
                                    target.category
                                )

                                null -> Unit
                            }
                        }
                    }
                ) { entry ->
                    when (entry) {
                        is LibraryEntry.BookEntry -> LibraryItem(
                            book = entry.book,
                            layout = layout,
                            hasSelectedItems = hasSelectedItems,
                            arranging = dragEnabled,
                            titlePosition = titlePosition,
                            readButton = readButton,
                            showProgress = showProgress,
                            selectBook = { select ->
                                selectBook(
                                    LibraryEvent.OnSelectBook(
                                        id = entry.book.data.id,
                                        select = select
                                    )
                                )
                            },
                            navigateToBookInfo = {
                                navigateToBookInfo(
                                    LibraryEvent.OnNavigateToBookInfo(entry.book.data.id)
                                )
                            },
                            navigateToReader = {
                                navigateToReader(
                                    LibraryEvent.OnNavigateToReader(entry.book.data.id)
                                )
                            }
                        )

                        is LibraryEntry.SubShelfEntry -> when (layout) {
                            LibraryLayout.LIST -> LibrarySubShelfListItem(
                                entry = entry,
                                onOpen = { openSubShelf(entry.category) },
                                onEdit = { editSubShelf(entry.category) }
                            )

                            LibraryLayout.GRID -> LibrarySubShelfGridTile(
                                entry = entry,
                                titlePosition = titlePosition,
                                highlighted = false,
                                onOpen = { openSubShelf(entry.category) },
                                onEdit = { editSubShelf(entry.category) }
                            )
                        }
                    }
                }
            }

            LibraryEmptyPlaceholder(
                isLoading = isLoading,
                isRefreshing = isRefreshing,
                isBooksEmpty = page.value.entries.isEmpty(),
                navigateToBrowse = navigateToBrowse
            )
        }
    }
}
