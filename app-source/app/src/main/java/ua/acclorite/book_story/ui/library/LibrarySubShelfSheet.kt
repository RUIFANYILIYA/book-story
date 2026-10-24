/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.model.LibraryLayout
import ua.acclorite.book_story.presentation.library.model.LibraryTitlePosition
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.components.common.LazyVerticalGridWithScrollbar
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.modal_bottom_sheet.ModalBottomSheet

/**
 * A sub-shelf opened as a sheet that slides up from the bottom.
 *
 * It used to open *into* the shelf: the grid was replaced by the folder's books and the top bar
 * grew a back arrow, which made a folder feel like a place you navigate to. A folder is not a
 * place — it is a smaller shelf — so it now arrives over the shelf it belongs to and leaves the
 * same way, with the shelf still there underneath when it closes.
 *
 * The books are laid out with the same item composables as the shelf itself, so a book looks the
 * same inside a folder as outside it.
 */
@Composable
fun LibrarySubShelfSheet(
    subShelf: Category,
    books: List<SelectableBook>,
    layout: LibraryLayout,
    titlePosition: LibraryTitlePosition,
    gridSize: Int,
    autoGridSize: Boolean,
    readButton: Boolean,
    showProgress: Boolean,
    hasSelectedItems: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    selectBook: (book: SelectableBook, select: Boolean?) -> Unit,
    navigateToBookInfo: (book: SelectableBook) -> Unit,
    navigateToReader: (book: SelectableBook) -> Unit
) {
    ModalBottomSheet(
        modifier = Modifier.fillMaxHeight(0.86f),
        hasFixedHeight = true,
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    StyledText(
                        text = subShelf.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.size(2.dp))
                    StyledText(
                        text = stringResource(
                            id = R.string.sub_shelf_book_count,
                            books.size
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                }

                // Renaming and deleting a folder lives on the folder itself, and this sheet is now
                // the folder — the top bar no longer knows about it.
                IconButton(
                    icon = Icons.Outlined.Edit,
                    contentDescription = R.string.edit_sub_shelf,
                    disableOnClick = false
                ) {
                    onEdit()
                }

                IconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = R.string.exit_sub_shelf_content_desc,
                    disableOnClick = false
                ) {
                    onDismiss()
                }
            }

            if (books.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StyledText(
                        text = stringResource(id = R.string.sub_shelf_empty),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            } else {
                when (layout) {
                    LibraryLayout.LIST -> LazyColumnWithScrollbar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(books.size, key = { books[it].data.id }) { index ->
                            val book = books[index]
                            LibraryItem(
                                book = book,
                                layout = LibraryLayout.LIST,
                                hasSelectedItems = hasSelectedItems,
                                arranging = false,
                                titlePosition = titlePosition,
                                readButton = readButton,
                                showProgress = showProgress,
                                selectBook = { select -> selectBook(book, select) },
                                navigateToBookInfo = { navigateToBookInfo(book) },
                                navigateToReader = { navigateToReader(book) }
                            )
                        }
                    }

                    LibraryLayout.GRID -> LazyVerticalGridWithScrollbar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        columns = if (autoGridSize) GridCells.Adaptive(120.dp)
                        else GridCells.Fixed(gridSize.coerceAtLeast(1)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(books, key = { it.data.id }) { book ->
                            LibraryItem(
                                book = book,
                                layout = LibraryLayout.GRID,
                                hasSelectedItems = hasSelectedItems,
                                arranging = false,
                                titlePosition = titlePosition,
                                readButton = readButton,
                                showProgress = showProgress,
                                selectBook = { select -> selectBook(book, select) },
                                navigateToBookInfo = { navigateToBookInfo(book) },
                                navigateToReader = { navigateToReader(book) }
                            )
                        }
                    }
                }
            }
        }
    }
}
