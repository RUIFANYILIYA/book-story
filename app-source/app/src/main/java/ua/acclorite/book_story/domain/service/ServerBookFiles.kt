/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.service

import android.net.Uri
import java.io.File

/**
 * Where things fetched from a library server live on this device.
 *
 * The directory is keyed by the server's book id rather than by filename, so re-importing a renamed
 * book cannot leave the previous copy behind — and so deleting the row can take its bytes with it.
 */
interface ServerBookFiles {
    fun dirFor(serverBookId: String): File

    /** Removes the directory and everything under it. True when nothing is left. */
    fun delete(serverBookId: String): Boolean

    /** Cached cover of a catalogue entry, kept outside the book directories on purpose. */
    fun coverFor(serverBookId: String): File

    /** Writes the bytes as a decodable image file, or null when they are not one. */
    fun saveCover(serverBookId: String, bytes: ByteArray): Uri?

    /** Drops every cached cover, e.g. once the account is gone. */
    fun clearCovers()
}
