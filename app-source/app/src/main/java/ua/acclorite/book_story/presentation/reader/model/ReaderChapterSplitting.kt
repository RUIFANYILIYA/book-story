/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.annotation.StringRes
import ua.acclorite.book_story.R

enum class ReaderChapterSplitting(@StringRes val title: Int) {
    AUTOMATIC(R.string.chapter_splitting_automatic),
    OFF(R.string.chapter_splitting_off)
}
