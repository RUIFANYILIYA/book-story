/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.model.remote

import android.net.Uri
import androidx.compose.runtime.Immutable

/** A book as the library server describes it, before anything is downloaded. */
@Immutable
data class RemoteBook(
    val id: String,
    val title: String,
    val author: String,
    val formats: List<String>,
    val sizeBytes: Long,
    /** Reading percentage recorded on the server, 0..100, null when never opened there. */
    val percent: Double?,
    /** The server's cover, already written into this device's cover cache. */
    val coverImage: Uri? = null,
    /** Its file is on this device and filed into the local library. */
    val inLibrary: Boolean = false
)
