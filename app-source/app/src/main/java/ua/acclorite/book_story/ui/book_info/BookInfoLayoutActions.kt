/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.book_info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.book_info.BookInfoEvent

@Composable
fun BookInfoLayoutActions(
    cloudDeletable: Boolean,
    cloudDeleteError: String?,
    showMoveDialog: (BookInfoEvent.OnShowMoveDialog) -> Unit,
    showDeleteDialog: (BookInfoEvent.OnShowDeleteDialog) -> Unit,
    showCloudDeleteDialog: (BookInfoEvent.OnShowCloudDeleteDialog) -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .clip(MaterialTheme.shapes.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BookInfoLayoutActionsItem(
                modifier = Modifier.weight(1f),
                alignmentStart = true,
                title = stringResource(id = R.string.move),
                icon = Icons.Default.MoveUp,
                onClick = {
                    showMoveDialog(BookInfoEvent.OnShowMoveDialog)
                }
            )

            BookInfoLayoutActionsItem(
                modifier = Modifier.weight(1f),
                alignmentStart = false,
                title = stringResource(id = R.string.delete),
                icon = Icons.Default.Delete,
                onClick = {
                    showDeleteDialog(BookInfoEvent.OnShowDeleteDialog)
                }
            )
        }

        // Only offered for books that came from a server and to accounts the server would allow;
        // the batch route answers anyone else with 403.
        if (cloudDeletable) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 2.dp)
                    .clip(MaterialTheme.shapes.large),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                BookInfoLayoutActionsItem(
                    modifier = Modifier.fillMaxWidth(),
                    alignmentStart = true,
                    title = stringResource(id = R.string.delete_cloud),
                    icon = Icons.Default.CloudOff,
                    onClick = {
                        showCloudDeleteDialog(BookInfoEvent.OnShowCloudDeleteDialog)
                    }
                )
            }
        }

        cloudDeleteError?.let { message ->
            Text(
                text = stringResource(id = R.string.delete_cloud_failed) + ": " + message,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}