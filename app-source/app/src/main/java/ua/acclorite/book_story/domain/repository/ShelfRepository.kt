/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ua.acclorite.book_story.domain.model.remote.RemoteBook
import ua.acclorite.book_story.domain.model.remote.RemoteDownload

interface ShelfRepository {

    /** The catalogue as last synced, with everything already on the device flagged. */
    val remoteBooks: StateFlow<List<RemoteBook>>

    val syncing: StateFlow<Boolean>

    /** server book id -> what its download is doing right now. Absent means idle. */
    val downloads: StateFlow<Map<String, RemoteDownload>>

    /** Emits a server book id once its file is on the device and filed into the library. */
    val arrivals: SharedFlow<String>

    /** Whether a server and a session are configured at all. */
    fun connected(): Boolean

    /**
     * Pulls the whole catalogue, following the signed page cursors until the last page, and refreshes
     * the "already on this device" flags. Covers keep arriving afterwards on their own.
     */
    suspend fun sync(): Result<List<RemoteBook>>

    /**
     * Pulls the server's shelf tree and files this device's books into it.
     *
     * Shelves are matched by name and level, so one that already exists locally is reused rather
     * than duplicated. Local shelves the server does not have are left alone: deleting a shelf on
     * one device should not silently empty it on another, and a phone-only shelf is a normal thing
     * to have.
     */
    suspend fun syncShelves(): Result<ShelfSyncResult>

    /**
     * What the server holds that this device does not, or holds a newer copy of.
     *
     * Reads the catalogue and nothing else: no book bytes move, so this is safe to call on a
     * metered connection and cheap enough to be a button rather than a background poll.
     */
    suspend fun checkServerChanges(): Result<ServerChanges>

    /** Queues everything a [checkServerChanges] found. */
    fun downloadChanges(changes: ServerChanges)

    /** Download one book and file it into the local library so the reader can open it. */
    suspend fun importBook(serverBookId: String, onProgress: (Float) -> Unit): Result<Unit>

    /** Queue one book. A failed entry is queued again, an in-flight one is left alone. */
    fun download(serverBookId: String)

    /**
     * Re-take one book whose bytes changed on the server, rewriting the local row in place.
     * Same progress map as [download], so the screen needs no second mechanism.
     */
    fun updateBook(serverBookId: String)

    /** Queue every catalogue entry that is not on the device yet. */
    fun downloadAll()

    /** Re-checks which catalogue entries are already on the device, e.g. after a local deletion. */
    suspend fun refreshFlags()

    /** Soft-deletes the book on the server; its bytes are reclaimed there after the retention window. */
    suspend fun deleteOnServer(serverBookId: String): Result<Unit>

    /** Whether this account may delete remotely at all — the batch route is capability-gated. */
    fun canDeleteOnServer(): Boolean

    /** Forgets the cached covers along with the account they came from. */
    fun forgetCovers()
}

/**
 * What one shelf sync did, in the terms the person who pressed the button cares about: how many
 * shelves the server has, how many of them were new here, and how many books changed shelf.
 *
 * `booksMatched` counts the books no server id could place — picked from storage, or taken from a
 * different server — that the content pass identified by hash instead. They are filed like any
 * other, but the number is worth its own line: it is the answer to "why was my shelf empty".
 */
data class ShelfSyncResult(
    val serverShelves: Int = 0,
    val shelvesCreated: Int = 0,
    val booksFiled: Int = 0,
    val booksMatched: Int = 0
)

/**
 * What one "check for new books" found.
 *
 * `newBooks` are on the server and not on this device; `updatedBooks` are on both, but the
 * server's copy of the bytes has changed since this device took them. The split matters because
 * the two are taken differently — one is a download, the other rewrites a row the reader already
 * has a position in.
 */
data class ServerChanges(
    val newBooks: List<RemoteBook> = emptyList(),
    val updatedBooks: List<RemoteBook> = emptyList()
) {
    val isEmpty: Boolean get() = newBooks.isEmpty() && updatedBooks.isEmpty()
    val total: Int get() = newBooks.size + updatedBooks.size
}
