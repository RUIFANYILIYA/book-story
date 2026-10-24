/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.book_info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.presentation.book_info.BookInfoEvent
import ua.acclorite.book_story.ui.common.components.common.AsyncCoverImage
import ua.acclorite.book_story.ui.common.components.common.BookCoverFallback
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable

@Composable
fun BookInfoLayoutInfoCover(
    book: Book,
    showChangeCoverBottomSheet: (BookInfoEvent.OnShowChangeCoverBottomSheet) -> Unit
) {
    val height = remember { 220.dp }
    val width = remember(height) { height * 0.66f }

    Box(
        modifier = Modifier
            .height(height)
            .width(width)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .noRippleClickable(
                onClick = {},
                onLongClick = {
                    showChangeCoverBottomSheet(BookInfoEvent.OnShowChangeCoverBottomSheet)
                }
            )
    ) {
        if (book.coverImage != null) {
            AsyncCoverImage(
                uri = book.coverImage,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
            )
        } else {
            BookCoverFallback(
                title = book.title,
                author = book.author.asString(),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}