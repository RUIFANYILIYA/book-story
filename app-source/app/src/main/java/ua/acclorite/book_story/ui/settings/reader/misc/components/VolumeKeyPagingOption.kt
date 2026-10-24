/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.misc.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.settings.SwitchWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings

@Composable
fun VolumeKeyPagingOption() {
    val settings = LocalSettings.current

    SwitchWithTitle(
        selected = settings.volumeKeyPaging.value,
        title = stringResource(id = R.string.volume_key_paging_option),
        onClick = {
            settings.volumeKeyPaging.update(!settings.volumeKeyPaging.lastValue)
        }
    )
}
