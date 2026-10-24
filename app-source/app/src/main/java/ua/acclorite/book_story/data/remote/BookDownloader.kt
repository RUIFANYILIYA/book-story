/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.remote

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Range-respecting downloader for library files.
 *
 * Three rules come from how the server actually behaves, not from preference:
 *  - the resume cursor is the temporary file's real length, never a counter that can drift;
 *  - a `200` where a `206` was asked for means the peer ignored the Range header, so the partial
 *    file is discarded rather than appended to (appending would splice the book onto itself);
 *  - the assembled file is checked against `X-Content-Sha256` before it is promoted, because a
 *    half-written book otherwise opens and reads fine until page 400.
 */
@Singleton
class BookDownloader @Inject constructor(
    private val http: OkHttpClient,
    private val tokens: TokenStore
) {
    suspend fun download(
        fileId: String,
        sha256: String,
        dest: File,
        /** The size the catalogue reported, so the HEAD below can be skipped. */
        knownSize: Long = 0L,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val baseUrl = tokens.baseUrl() ?: throw IOException("No server configured")
        val endpoint = baseUrl.trimEnd('/') + "/api/v1/files/$fileId/download"

        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")

        var total = knownSize
        var etag: String? = null
        var digest = sha256

        // The catalogue already answered the two things a HEAD is asked for — the size and the hash
        // come with the book's detail — so a fresh download does not ask again. That request per
        // book is what pushed a forty-three book batch past the server's request budget, and every
        // refusal it earns costs a wait of one to four seconds. A resume still asks, because a
        // resume needs the validator.
        if (total <= 0L || temp.exists()) {
            http.newCall(Request.Builder().url(endpoint).head().build()).execute().use { head ->
                if (!head.isSuccessful) throw IOException("HEAD failed with ${head.code}")
                head.header("Content-Length")?.toLongOrNull()?.let { total = it }
                head.header("ETag")?.let { etag = it }
                head.header("X-Content-Sha256")
                    ?.takeIf { it.length == SHA256_HEX_LENGTH }
                    ?.let { digest = it }
            }
        }

        var satisfied = false
        while (!satisfied && (total < 0 || temp.length() < total)) {
            val written = temp.length()
            val request = Request.Builder().url(endpoint).get()
            if (total > 0) {
                val end = minOf(written + SEGMENT_BYTES, total) - 1
                request.header("Range", "bytes=$written-$end")
                etag?.let { request.header("If-Range", it) }
            }

            http.newCall(request.build()).execute().use { response ->
                when {
                    // The server says our cursor is already past the end: the file is complete.
                    response.code == 416 -> satisfied = true
                    !response.isSuccessful -> throw IOException(
                        "Download failed with ${response.code}"
                    )
                    else -> {
                        // 206 honours our range and continues the file; anything else restarts it.
                        val append = response.code == 206
                        if (!append) temp.delete()
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(temp, append).use { output -> input.copyTo(output) }
                        } ?: throw IOException("Empty response body")
                    }
                }
            }

            if (!satisfied && temp.length() == written) {
                throw IOException("Transfer stalled at $written bytes")
            }
            onProgress(temp.length(), total)
            if (total <= 0) break
        }

        if (digest.isNotBlank()) {
            val actual = temp.sha256Hex()
            if (!actual.equals(digest, ignoreCase = true)) {
                temp.delete()
                throw IOException("Checksum mismatch: expected $digest, got $actual")
            }
        }

        if (dest.exists()) dest.delete()
        if (!temp.renameTo(dest)) throw IOException("Could not move the file into place")
        dest
    }

    private fun File.sha256Hex(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SEGMENT_BYTES = 16L * 1024L * 1024L
        const val SHA256_HEX_LENGTH = 64
    }
}
