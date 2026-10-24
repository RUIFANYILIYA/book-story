/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.library.LibraryEvent
import ua.acclorite.book_story.ui.common.components.dialog.Dialog

/**
 * A book that came from a server survives this; one that was picked from storage does not. The
 * wording says which, because "delete" on a downloaded book and "delete" on a local file look the
 * same until it is too late to be careful.
 */
@Composable
fun LibraryDeleteLocalDialog(
    bookTitle: String,
    actionDeleteLocal: (LibraryEvent.OnActionDeleteLocalBook) -> Unit,
    dismissDialog: (LibraryEvent.OnDismissDeleteLocal) -> Unit
) {
    Dialog(
        title = stringResource(id = R.string.library_delete_local_title),
        icon = Icons.Filled.DeleteOutline,
        description = stringResource(
            id = R.string.library_delete_local_description,
            bookTitle
        ),
        actionEnabled = true,
        onDismiss = {
            dismissDialog(LibraryEvent.OnDismissDeleteLocal)
        },
        onAction = {
            actionDeleteLocal(LibraryEvent.OnActionDeleteLocalBook)
        },
        withContent = false
    )
}
