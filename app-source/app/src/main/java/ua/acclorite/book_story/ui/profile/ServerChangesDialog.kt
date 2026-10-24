/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.remote.RemoteBook
import ua.acclorite.book_story.domain.repository.ServerChanges
import ua.acclorite.book_story.ui.common.components.dialog.Dialog
import ua.acclorite.book_story.ui.common.components.common.StyledText

/**
 * "The server has books you don't — take them?"
 *
 * The list is the point: a reader who sees 《银河帝国》 and 《沙丘》 named can decide in one
 * glance, while "3 new books" is a question they can only answer by trusting the button. New and
 * updated are shown separately because they are taken differently — one files a new row, the
 * other rewrites a row the reader already has a position in.
 */
@Composable
fun ServerChangesDialog(
    changes: ServerChanges,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        title = stringResource(id = R.string.profile_books_dialog_title),
        icon = Icons.Outlined.CloudDownload,
        description = stringResource(
            id = R.string.profile_books_dialog_description,
            changes.newBooks.size,
            changes.updatedBooks.size
        ),
        actionEnabled = true,
        onDismiss = onDismiss,
        onAction = onDownload,
        secondaryAction = stringResource(id = R.string.profile_books_dialog_later),
        onSecondaryAction = onDismiss,
        withContent = true,
        items = {
            items(changes.newBooks, key = { it.id }) { book ->
                ServerChangesRow(book, stringResource(id = R.string.profile_books_badge_new))
            }
            items(changes.updatedBooks, key = { it.id }) { book ->
                ServerChangesRow(book, stringResource(id = R.string.profile_books_badge_updated))
            }
        }
    )
}

@Composable
private fun ServerChangesRow(book: RemoteBook, badge: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StyledText(
            text = book.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (book.author.isNotBlank()) {
            StyledText(
                text = book.author,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
