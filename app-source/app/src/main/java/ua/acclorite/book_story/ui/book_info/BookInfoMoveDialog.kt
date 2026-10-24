/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.book_info

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.book_info.BookInfoEvent
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.dialog.CheckboxDialogItem
import ua.acclorite.book_story.ui.common.components.dialog.Dialog

@Composable
fun BookInfoMoveDialog(
    book: Book,
    categories: List<Category>,
    actionMoveDialog: (BookInfoEvent.OnActionMoveDialog) -> Unit,
    dismissDialog: (BookInfoEvent.OnDismissDialog) -> Unit,
    navigateToLibrarySettings: (BookInfoEvent.OnNavigateToLibrarySettings) -> Unit
) {
    // Parents first, each followed by its own sub-shelves: the checkbox list is flat, and ordering
    // by `order` alone would interleave them, since a sub-shelf's order is numbered among its own
    // siblings.
    val selectableCategories = remember(categories) {
        val subShelvesByParent = categories
            .filter { it.parentId != null }
            .groupBy { it.parentId }

        categories
            .filter { it.id != -1 && it.parentId == null }
            .sortedBy { it.order }
            .flatMap { parent ->
                listOf(parent) + subShelvesByParent[parent.id].orEmpty().sortedBy { it.order }
            }
    }
    val selectedCategories = remember {
        mutableStateListOf<Category>().apply {
            clear()
            categories.forEach { category ->
                if (book.categories.any { it == category.id }) {
                    add(category)
                }
            }
        }
    }

    Dialog(
        title = stringResource(id = R.string.move),
        description = null,
        actionEnabled = true,
        onDismiss = { dismissDialog(BookInfoEvent.OnDismissDialog) },
        onAction = {
            actionMoveDialog(
                BookInfoEvent.OnActionMoveDialog(
                    selectedCategories = selectedCategories
                )
            )
        },
        secondaryAction = stringResource(id = R.string.edit),
        onSecondaryAction = {
            navigateToLibrarySettings(BookInfoEvent.OnNavigateToLibrarySettings)
            dismissDialog(BookInfoEvent.OnDismissDialog)
        },
        withContent = true,
        items = {
            items(selectableCategories) { category ->
                val selected = remember(
                    category,
                    selectedCategories
                ) {
                    derivedStateOf {
                        selectedCategories.any { it == category }
                    }
                }

                CheckboxDialogItem(
                    selected = selected.value,
                    title = if (category.parentId == null) category.title
                    else "└ ${category.title}"
                ) {
                    if (selected.value) {
                        selectedCategories.remove(category)
                    } else {
                        // A book shows up in at most one sub-shelf, so picking one releases the rest.
                        if (category.parentId != null) {
                            selectedCategories.removeAll { it.parentId != null }
                        }
                        selectedCategories.add(category)
                    }
                }
            }

            if (selectableCategories.isEmpty()) {
                item {
                    StyledText(
                        text = stringResource(id = R.string.categories_empty),
                        modifier = Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    )
}