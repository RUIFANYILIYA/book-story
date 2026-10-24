/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.library.model

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.domain.model.remote.RemoteBook
import ua.acclorite.book_story.domain.model.remote.RemoteDownload

@Immutable
data class SelectableBook(
    val data: Book,
    val selected: Boolean,
    /**
     * Set for a catalogue entry that is only a title and a cover so far: tapping it downloads the
     * file instead of opening the reader.
     */
    val remote: RemoteBook? = null,
    val remoteDownload: RemoteDownload? = null
)
