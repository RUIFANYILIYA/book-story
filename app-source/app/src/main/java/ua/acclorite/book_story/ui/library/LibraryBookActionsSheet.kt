/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.common.components.modal_bottom_sheet.ModalBottomSheet

/**
 * What a long press on one book offers: the three things a reader actually wants to do with a book
 * they are not reading — keep it on top, put it on a shelf, or get rid of this copy. Selecting many
 * books at once is still there, one row down, because the actions that only make sense in bulk
 * (moving a pile, deleting a pile) live behind it.
 */
@Composable
fun LibraryBookActionsSheet(
    book: SelectableBook,
    onEditTitle: (SelectableBook) -> Unit,
    onEditAuthor: (SelectableBook) -> Unit,
    onTogglePinned: (SelectableBook) -> Unit,
    onMove: (SelectableBook) -> Unit,
    onDeleteLocal: (SelectableBook) -> Unit,
    onSelectMultiple: (SelectableBook) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        hasFixedHeight = false,
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = { onDismiss() },
        sheetGesturesEnabled = true
    ) {
        Text(
            text = book.data.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)
        )
        Text(
            text = book.data.author.getAsString().orEmpty().ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp)
        )
        Spacer(Modifier.height(8.dp))

        ActionRow(
            icon = Icons.Outlined.EditNote,
            label = stringResource(id = R.string.library_book_edit_title)
        ) { onEditTitle(book) }

        ActionRow(
            icon = Icons.Outlined.Person,
            label = stringResource(id = R.string.library_book_edit_author)
        ) { onEditAuthor(book) }

        ActionRow(
            icon = Icons.Filled.PushPin,
            label = stringResource(
                id = if (book.data.pinned) R.string.library_book_unpin else R.string.library_book_pin
            )
        ) { onTogglePinned(book) }

        ActionRow(
            icon = Icons.Filled.DriveFileMove,
            label = stringResource(id = R.string.library_book_move)
        ) { onMove(book) }

        ActionRow(
            icon = Icons.Filled.DeleteOutline,
            label = stringResource(id = R.string.library_book_delete_local),
            tint = MaterialTheme.colorScheme.error
        ) { onDeleteLocal(book) }

        ActionRow(
            icon = Icons.Filled.Checklist,
            label = stringResource(id = R.string.library_book_select_multiple)
        ) { onSelectMultiple(book) }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}
