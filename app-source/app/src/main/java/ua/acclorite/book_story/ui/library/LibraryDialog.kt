/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.runtime.Composable
import ua.acclorite.book_story.core.Dialog
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.CombineRequest
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.presentation.library.LibraryScreen
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.settings.library.categories.components.LibraryCategoriesCreateDialog

@Composable
fun LibraryDialog(
    dialog: Dialog?,
    books: List<SelectableBook>,
    categories: List<Category>,
    selectedItemsCount: Int,
    editingSubShelf: Category?,
    combine: CombineRequest?,
    currentShelfId: Int?,
    actionMoveDialog: (LibraryEvent.OnActionMoveDialog) -> Unit,
    actionDeleteDialog: (LibraryEvent.OnActionDeleteDialog) -> Unit,
    createShelf: (LibraryEvent.OnCreateShelf) -> Unit,
    createSubShelf: (LibraryEvent.OnCreateSubShelf) -> Unit,
    renameSubShelf: (LibraryEvent.OnRenameSubShelf) -> Unit,
    deleteSubShelf: (LibraryEvent.OnDeleteSubShelf) -> Unit,
    confirmCombine: (LibraryEvent.OnConfirmCombine) -> Unit,
    dismissDialog: (LibraryEvent.OnDismissDialog) -> Unit,
    navigateToLibrarySettings: (LibraryEvent.OnNavigateToLibrarySettings) -> Unit
) {
    when (dialog) {
        LibraryScreen.MOVE_DIALOG -> {
            LibraryMoveDialog(
                books = books,
                categories = categories,
                currentShelfId = currentShelfId,
                actionMoveDialog = actionMoveDialog,
                dismissDialog = dismissDialog,
                navigateToLibrarySettings = navigateToLibrarySettings
            )
        }

        LibraryScreen.DELETE_DIALOG -> {
            LibraryDeleteDialog(
                selectedItemsCount = selectedItemsCount,
                actionDeleteDialog = actionDeleteDialog,
                dismissDialog = dismissDialog
            )
        }

        LibraryScreen.CREATE_SHELF_DIALOG -> {
            LibraryCategoriesCreateDialog(
                onDismiss = { dismissDialog(LibraryEvent.OnDismissDialog) },
                onAction = { title -> createShelf(LibraryEvent.OnCreateShelf(title)) }
            )
        }

        LibraryScreen.CREATE_SUB_SHELF_DIALOG -> {
            LibrarySubShelfDialog(
                subShelf = null,
                onDismiss = { dismissDialog(LibraryEvent.OnDismissDialog) },
                onCreate = { title -> createSubShelf(LibraryEvent.OnCreateSubShelf(title)) },
                onRename = {},
                onDelete = {}
            )
        }

        LibraryScreen.EDIT_SUB_SHELF_DIALOG -> {
            LibrarySubShelfDialog(
                subShelf = editingSubShelf,
                onDismiss = { dismissDialog(LibraryEvent.OnDismissDialog) },
                onCreate = {},
                onRename = { title -> renameSubShelf(LibraryEvent.OnRenameSubShelf(title)) },
                onDelete = { deleteSubShelf(LibraryEvent.OnDeleteSubShelf) }
            )
        }

        LibraryScreen.COMBINE_DIALOG -> {
            // The pair is held in state rather than in the dialog so a rotation mid-typing does not
            // lose which two books were put together.
            combine?.let { request ->
                LibraryCombineDialog(
                    draggedTitle = request.dragged.data.title,
                    targetTitle = request.target.data.title,
                    onDismiss = { dismissDialog(LibraryEvent.OnDismissDialog) },
                    onConfirm = { title -> confirmCombine(LibraryEvent.OnConfirmCombine(title)) }
                )
            }
        }
    }
}
