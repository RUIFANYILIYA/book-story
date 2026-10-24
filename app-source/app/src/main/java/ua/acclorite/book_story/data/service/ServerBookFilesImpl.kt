/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.service

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import ua.acclorite.book_story.domain.service.ServerBookFiles

@Singleton
class ServerBookFilesImpl @Inject constructor(
    private val application: Application
) : ServerBookFiles {

    override fun dirFor(serverBookId: String): File =
        File(application.filesDir, "ebookd/${safe(serverBookId)}")

    override fun delete(serverBookId: String): Boolean {
        val dir = dirFor(serverBookId)
        if (!dir.exists()) return true
        dir.deleteRecursively()
        return !dir.exists()
    }

    override fun coverFor(serverBookId: String): File =
        File(coversDir(), "${safe(serverBookId)}.webp")

    override fun saveCover(serverBookId: String, bytes: ByteArray): Uri? = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val file = coverFor(serverBookId)
        file.parentFile?.mkdirs()
        // The existence of this file is what tells a sync to stop asking, so it must never be
        // visible in a half-written state.
        val staging = File(file.parentFile, "${file.name}.tmp")
        BufferedOutputStream(FileOutputStream(staging)).use { output ->
            if (bitmap == null) {
                // A coverless book gets an SVG drawn by the server, and nothing here can decode it.
                // Writing the bytes anyway is what stops the shelf from asking for the same cover
                // again on every sync — the file's existence is the only record that this book's
                // cover is already known, and a missing file read as "ask again" turned a forty-book
                // library into thousands of cover requests competing with its own downloads. The
                // tile falls back to its colour block, exactly as it did while the file was absent.
                output.write(bytes)
            } else {
                val scaled = shrink(bitmap)
                val encoded = scaled.compress(Bitmap.CompressFormat.WEBP, 30, output)
                if (scaled !== bitmap) scaled.recycle()
                if (!encoded) return null
            }
        }
        if (!staging.renameTo(file)) {
            staging.delete()
            return null
        }
        file.toUri()
    }.getOrNull()

    override fun clearCovers() {
        coversDir().deleteRecursively()
    }

    private fun coversDir() = File(application.filesDir, "ebookd_covers")

    /** Shelf tiles are at most a few hundred pixels wide, so the cache stays small on a phone. */
    private fun shrink(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_COVER_PX) return bitmap
        val factor = MAX_COVER_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Book ids come from the network, so they never reach the filesystem unchanged. */
    private fun safe(id: String) =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "book" }

    private companion object {
        const val MAX_COVER_PX = 420
    }
}
