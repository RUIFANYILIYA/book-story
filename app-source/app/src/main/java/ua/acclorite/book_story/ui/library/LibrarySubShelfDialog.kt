/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.dialog.Dialog

/**
 * One dialog for both sub-shelf jobs: [subShelf] is `null` when creating one and the shelf being
 * edited otherwise. Editing also offers the delete action, so renaming and removing live together
 * rather than in two dialogs.
 */
@Composable
fun LibrarySubShelfDialog(
    subShelf: Category?,
    onDismiss: () -> Unit,
    onCreate: (title: String) -> Unit,
    onRename: (title: String) -> Unit,
    onDelete: () -> Unit
) {
    val editing = subShelf != null
    val title = remember(subShelf?.id) {
        mutableStateOf(subShelf?.title ?: "")
    }

    val focusRequester = remember { FocusRequester() }
    val focused = remember { mutableStateOf(false) }

    Dialog(
        title = stringResource(
            id = if (editing) R.string.edit_sub_shelf else R.string.create_sub_shelf
        ),
        icon = if (editing) null else Icons.AutoMirrored.Outlined.Label,
        description = null,
        actionEnabled = title.value.isNotBlank(),
        disableOnClick = false,
        onDismiss = { onDismiss() },
        onAction = {
            val name = title.value.replace("\n", "").trim()
            if (name.isNotEmpty()) {
                if (editing) onRename(name) else onCreate(name)
                onDismiss()
            }
        },
        secondaryAction = if (editing) stringResource(id = R.string.delete_sub_shelf) else null,
        onSecondaryAction = if (editing) {
            {
                onDelete()
                onDismiss()
            }
        } else {
            null
        },
        withContent = true,
        items = {
            item {
                OutlinedTextField(
                    value = title.value,
                    onValueChange = {
                        if (it.length <= 200) {
                            title.value = it
                        }
                    },
                    keyboardOptions = KeyboardOptions(KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned {
                            if (!focused.value) {
                                focusRequester.requestFocus()
                                focused.value = true
                            }
                        },
                    colors = TextFieldDefaults.colors(
                        errorContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    label = {
                        StyledText(
                            text = stringResource(id = R.string.sub_shelf_name),
                            maxLines = 1
                        )
                    }
                )
            }
        }
    )
}
