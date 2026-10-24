/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.annotation.StringRes
import ua.acclorite.book_story.R

/**
 * SCROLL keeps the whole book in one vertical list, PAGE splits it into screen-sized
 * pages that are swiped left and right.
 */
enum class ReaderReadingMode(@StringRes val title: Int) {
    SCROLL(R.string.reading_mode_scroll),
    PAGE(R.string.reading_mode_page)
}
