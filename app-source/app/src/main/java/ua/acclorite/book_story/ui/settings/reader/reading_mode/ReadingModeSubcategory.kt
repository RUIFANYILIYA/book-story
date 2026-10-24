/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:Suppress("FunctionName")

package ua.acclorite.book_story.ui.settings.reader.reading_mode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.reader.model.ReaderReadingMode
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategory
import ua.acclorite.book_story.ui.settings.reader.reading_mode.components.HorizontalGestureAlphaAnimOption
import ua.acclorite.book_story.ui.settings.reader.reading_mode.components.HorizontalGestureOption
import ua.acclorite.book_story.ui.settings.reader.reading_mode.components.HorizontalGesturePullAnimOption
import ua.acclorite.book_story.ui.settings.reader.reading_mode.components.HorizontalGestureScrollOption
import ua.acclorite.book_story.ui.settings.reader.reading_mode.components.HorizontalGestureSensitivityOption
import ua.acclorite.book_story.ui.settings.reader.reading_mode.components.ReadingModeOption

fun LazyListScope.ReadingModeSubcategory(
    titleColor: @Composable () -> Color = { MaterialTheme.colorScheme.primary },
    title: @Composable () -> String = { stringResource(id = R.string.reading_mode_reader_settings) },
    showTitle: Boolean = true,
    showDivider: Boolean = true
) {
    SettingsSubcategory(
        titleColor = titleColor,
        title = title,
        showTitle = showTitle,
        showDivider = showDivider
    ) {
        item {
            ReadingModeOption()
        }

        // These gestures only make sense while the book is one long vertical list.
        // In paged mode swiping is handled by the pager itself.
        item {
            val settings = LocalSettings.current

            if (settings.readingMode.value == ReaderReadingMode.SCROLL) {
                Column {
                    HorizontalGestureOption()
                    HorizontalGestureScrollOption()
                    HorizontalGestureSensitivityOption()
                    HorizontalGesturePullAnimOption()
                    HorizontalGestureAlphaAnimOption()
                }
            }
        }
    }
}