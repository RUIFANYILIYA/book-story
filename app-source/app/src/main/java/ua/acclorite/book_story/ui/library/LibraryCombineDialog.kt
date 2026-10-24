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
import androidx.compose.material.icons.outlined.CreateNewFolder
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.dialog.Dialog

/**
 * Asks what the two books that were just put together should be called.
 *
 * The field starts with the title of the book they were dropped onto: that is the name people
 * would have picked nine times out of ten, and it is editable, so it is a starting point rather
 * than a decision made for them.
 */
@Composable
fun LibraryCombineDialog(
    draggedTitle: String,
    targetTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String) -> Unit
) {
    val title = remember(targetTitle) {
        mutableStateOf(
            TextFieldValue(
                text = targetTitle,
                selection = TextRange(0, targetTitle.length)
            )
        )
    }

    val focusRequester = remember { FocusRequester() }
    val focused = remember { mutableStateOf(false) }

    Dialog(
        title = stringResource(id = R.string.combine_sub_shelf),
        icon = Icons.Outlined.CreateNewFolder,
        description = stringResource(
            id = R.string.combine_sub_shelf_description,
            draggedTitle,
            targetTitle
        ),
        actionEnabled = title.value.text.isNotBlank(),
        disableOnClick = false,
        onDismiss = { onDismiss() },
        onAction = {
            val name = title.value.text.replace("\n", "").trim()
            if (name.isNotEmpty()) {
                onConfirm(name)
                onDismiss()
            }
        },
        withContent = true,
        items = {
            item {
                OutlinedTextField(
                    value = title.value,
                    onValueChange = {
                        if (it.text.length <= 200) {
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
